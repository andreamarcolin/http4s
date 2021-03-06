/*
 * Copyright 2019 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.ember.client.internal

import org.http4s.ember.client._
import fs2.io.tcp._
import fs2.io.Network
import cats._
import cats.data.NonEmptyList
import cats.effect._
import cats.effect.kernel.Clock
import cats.syntax.all._
import scala.concurrent.duration._
import java.net.InetSocketAddress
import org.http4s._
import org.http4s.client.RequestKey
import org.typelevel.ci.CIString
import _root_.org.http4s.ember.core.{Encoder, Parser}
import _root_.fs2.io.tcp.SocketGroup
import _root_.fs2.io.tls._
import org.typelevel.keypool._
import javax.net.ssl.SNIHostName
import org.http4s.headers.{Connection, Date, `User-Agent`}
import _root_.org.http4s.ember.core.Util.durationToFinite

private[client] object ClientHelpers {
  def requestToSocketWithKey[F[_]: Sync: Network](
      request: Request[F],
      tlsContextOpt: Option[TLSContext],
      sg: SocketGroup,
      additionalSocketOptions: List[SocketOptionMapping[_]]
  ): Resource[F, RequestKeySocket[F]] = {
    val requestKey = RequestKey.fromRequest(request)
    requestKeyToSocketWithKey[F](
      requestKey,
      tlsContextOpt,
      sg,
      additionalSocketOptions
    )
  }

  def requestKeyToSocketWithKey[F[_]: Sync: Network](
      requestKey: RequestKey,
      tlsContextOpt: Option[TLSContext],
      sg: SocketGroup,
      additionalSocketOptions: List[SocketOptionMapping[_]]
  ): Resource[F, RequestKeySocket[F]] =
    for {
      address <- Resource.eval(getAddress(requestKey))
      initSocket <- sg.client[F](address, additionalSocketOptions = additionalSocketOptions)
      socket <- {
        if (requestKey.scheme === Uri.Scheme.https)
          tlsContextOpt.fold[Resource[F, Socket[F]]] {
            ApplicativeThrow[Resource[F, *]].raiseError(
              new Throwable("EmberClient Not Configured for Https")
            )
          } { tlsContext =>
            tlsContext
              .client(
                initSocket,
                TLSParameters(serverNames = Some(List(new SNIHostName(address.getHostName)))))
              .widen[Socket[F]]
          }
        else initSocket.pure[Resource[F, *]]
      }
    } yield RequestKeySocket(socket, requestKey)

  def request[F[_]: Async](
      request: Request[F],
      requestKeySocket: RequestKeySocket[F],
      reuseable: Ref[F, Reusable],
      chunkSize: Int,
      maxResponseHeaderSize: Int,
      idleTimeout: Duration,
      timeout: Duration,
      userAgent: Option[`User-Agent`]
  ): F[Response[F]] = {

    def writeRequestToSocket(
        req: Request[F],
        socket: Socket[F],
        timeout: Option[FiniteDuration]): F[Unit] =
      Encoder
        .reqToBytes(req)
        .through(socket.writes(timeout))
        .compile
        .drain

    def writeRead(req: Request[F]): F[Response[F]] =
      writeRequestToSocket(req, requestKeySocket.socket, durationToFinite(idleTimeout)) >> {
        Parser.Response
          .parser(maxResponseHeaderSize, durationToFinite(timeout))(
            requestKeySocket.socket.reads(chunkSize, durationToFinite(idleTimeout))
          )
          .map(_._1)
      }

    for {
      processedReq <- preprocessRequest(request, userAgent)
      resp <- writeRead(processedReq)
    } yield postProcessResponse(processedReq, resp, reuseable)
  }

  private[internal] def preprocessRequest[F[_]: Monad: Clock](
      req: Request[F],
      userAgent: Option[`User-Agent`]): F[Request[F]] = {
    val connection = req.headers
      .get(Connection)
      .fold(Connection(NonEmptyList.of(CIString("keep-alive"))))(identity)
    val userAgentHeader: Option[`User-Agent`] = req.headers.get(`User-Agent`).orElse(userAgent)
    for {
      date <- req.headers.get(Date).fold(HttpDate.current[F].map(Date(_)))(_.pure[F])
    } yield req
      .putHeaders(date, connection)
      .putHeaders(userAgentHeader.toSeq: _*)
  }

  private[internal] def postProcessResponse[F[_]: Concurrent](
      req: Request[F],
      resp: Response[F],
      canBeReused: Ref[F, Reusable]): Response[F] = {
    val out = resp.copy(
      body = resp.body.onFinalizeCaseWeak {
        case Resource.ExitCase.Succeeded =>
          val requestClose = req.headers.get(Connection).exists(_.hasClose)
          val responseClose = resp.headers.get(Connection).exists(_.hasClose)

          if (requestClose || responseClose) Applicative[F].unit
          else canBeReused.set(Reusable.Reuse)
        case Resource.ExitCase.Canceled => Applicative[F].unit
        case Resource.ExitCase.Errored(_) => Applicative[F].unit
      }
    )
    out
  }

  // https://github.com/http4s/http4s/blob/main/blaze-client/src/main/scala/org/http4s/client/blaze/Http1Support.scala#L86
  private def getAddress[F[_]: Sync](requestKey: RequestKey): F[InetSocketAddress] =
    requestKey match {
      case RequestKey(s, auth) =>
        val port = auth.port.getOrElse(if (s == Uri.Scheme.https) 443 else 80)
        val host = auth.host.value
        Sync[F].delay(new InetSocketAddress(host, port))
    }

  // Assumes that the request doesn't have fancy finalizers besides shutting down the pool
  private[client] def getValidManaged[F[_]: Sync](
      pool: KeyPool[F, RequestKey, (RequestKeySocket[F], F[Unit])],
      request: Request[F]): Resource[F, Managed[F, (RequestKeySocket[F], F[Unit])]] =
    pool.take(RequestKey.fromRequest(request)).flatMap { managed =>
      Resource
        .eval(managed.value._1.socket.isOpen)
        .ifM(
          managed.pure[Resource[F, *]],
          // Already Closed,
          // The Resource Scopes Aren't doing us anything
          // if we have max removed from pool we will need to revisit
          if (managed.isReused) {
            Resource.eval(managed.canBeReused.set(Reusable.DontReuse)) >>
              getValidManaged(pool, request)
          } else
            Resource.eval(Sync[F].raiseError(
              new java.net.SocketException("Fresh connection from pool was not open")))
        )
    }
}
