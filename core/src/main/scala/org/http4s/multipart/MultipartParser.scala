/*
 * Copyright 2013 http4s.org
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

package org.http4s
package multipart

import cats._
import cats.effect.Concurrent
import cats.syntax.all._
import fs2.{Chunk, Pipe, Pull, Pure, Stream}
import fs2.io.file.Files
import java.nio.file.{Path, StandardOpenOption}

/** A low-level multipart-parsing pipe.  Most end users will prefer EntityDecoder[Multipart]. */
object MultipartParser {
  private[this] val logger = org.log4s.getLogger

  private[this] val CRLFBytesN = Array[Byte]('\r', '\n')
  private[this] val DoubleCRLFBytesN = Array[Byte]('\r', '\n', '\r', '\n')
  private[this] val DashDashBytesN = Array[Byte]('-', '-')
  private[this] val BoundaryBytesN: Boundary => Array[Byte] = boundary =>
    boundary.value.getBytes("UTF-8")
  val StartLineBytesN: Boundary => Array[Byte] = BoundaryBytesN.andThen(DashDashBytesN ++ _)

  private[this] val ExpectedBytesN: Boundary => Array[Byte] =
    BoundaryBytesN.andThen(CRLFBytesN ++ DashDashBytesN ++ _)
  private[this] val dashByte: Byte = '-'.toByte
  private[this] val streamEmpty = Stream.empty
  private[this] val PullUnit = Pull.pure[Pure, Unit](())

  private type SplitStream[F[_]] = Pull[F, Nothing, (Stream[F, Byte], Stream[F, Byte])]
  private type SplitFileStream[F[_]] =
    Pull[F, Nothing, (Stream[F, Byte], Stream[F, Byte], Option[Path])]

  def parseStreamed[F[_]: Concurrent](
      boundary: Boundary,
      limit: Int = 1024): Pipe[F, Byte, Multipart[F]] = { st =>
    ignorePrelude[F](boundary, st, limit)
      .fold(Vector.empty[Part[F]])(_ :+ _)
      .map(Multipart(_, boundary))
  }

  def parseToPartsStream[F[_]: Concurrent](
      boundary: Boundary,
      limit: Int = 1024): Pipe[F, Byte, Part[F]] = { st =>
    ignorePrelude[F](boundary, st, limit)
  }

  private def splitAndIgnorePrev[F[_]](
      values: Array[Byte],
      state: Int,
      c: Chunk[Byte]): (Int, Stream[F, Byte]) = {
    var i = 0
    var currState = state
    val len = values.length
    while (currState < len && i < c.size) {
      if (c(i) == values(currState))
        currState += 1
      else if (c(i) == values(0))
        currState = 1
      else
        currState = 0
      i += 1
    }

    if (currState == 0)
      (0, Stream.empty)
    else if (currState == len)
      (currState, Stream.chunk(c.drop(i)))
    else
      (currState, Stream.empty)
  }

  /** Split a chunk in the case of a complete match:
    *
    * If it is a chunk that is between a partial match
    * (middleChunked), consider the prior partial match
    * as part of the data to emit.
    *
    * If it is a fully matched, fresh chunk (no carry over partial match),
    * emit everything until the match, and everything after the match.
    *
    * If it is the continuation of a partial match,
    * emit everything after the partial match.
    */
  private def splitCompleteMatch[F[_]](
      middleChunked: Boolean,
      sti: Int,
      i: Int,
      acc: Stream[F, Byte],
      carry: Stream[F, Byte],
      c: Chunk[Byte]
  ): (Int, Stream[F, Byte], Stream[F, Byte]) =
    if (middleChunked)
      (
        sti,
        //Emit the partial match as well
        acc ++ carry ++ Stream.chunk(c.take(i - sti)),
        Stream.chunk(c.drop(i))
      ) //Emit after the match
    else
      (
        sti,
        acc, //block completes partial match, so do not emit carry
        Stream.chunk(c.drop(i))
      ) //Emit everything after the match

  /** Split a chunk in the case of a partial match:
    *
    * DO NOT USE. Was made private[http4s] because
    * Jose messed up hard like 5 patches ago and now it breaks bincompat to
    * remove.
    */
  private def splitPartialMatch[F[_]](
      middleChunked: Boolean,
      currState: Int,
      i: Int,
      acc: Stream[F, Byte],
      carry: Stream[F, Byte],
      c: Chunk[Byte]
  ): (Int, Stream[F, Byte], Stream[F, Byte]) = {
    val ixx = i - currState
    if (middleChunked) {
      val (lchunk, rchunk) = c.splitAt(ixx)
      (currState, acc ++ carry ++ Stream.chunk(lchunk), Stream.chunk(rchunk))
    } else
      (currState, acc, carry ++ Stream.chunk(c))
  }

  /** Split a chunk as part of either a left or right
    * stream depending on the byte sequence in `values`.
    *
    * `state` represents the current counter position
    * for `values`, which is necessary to keep track of in the
    * case of partial matches.
    *
    * `acc` holds the cumulative left stream values,
    * and `carry` holds the values that may possibly
    * be the byte sequence. As such, carry is re-emitted if it was an
    * incomplete match, or ignored (as such excluding the sequence
    * from the subsequent split stream).
    */
  private[http4s] def splitOnChunk[F[_]](
      values: Array[Byte],
      state: Int,
      c: Chunk[Byte],
      acc: Stream[F, Byte],
      carry: Stream[F, Byte]): (Int, Stream[F, Byte], Stream[F, Byte]) = {
    var i = 0
    var currState = state
    val len = values.length
    while (currState < len && i < c.size) {
      if (c(i) == values(currState))
        currState += 1
      else if (c(i) == values(0))
        currState = 1
      else
        currState = 0
      i += 1
    }
    //It will only be zero if
    //the chunk matches from the very beginning,
    //since currstate can never be greater than
    //(i + state).
    val middleChunked = i + state - currState > 0

    if (currState == 0)
      (0, acc ++ carry ++ Stream.chunk(c), Stream.empty)
    else if (currState == len)
      splitCompleteMatch(middleChunked, currState, i, acc, carry, c)
    else
      splitPartialMatch(middleChunked, currState, i, acc, carry, c)
  }

  /** The first part of our streaming stages:
    *
    * Ignore the prelude and remove the first boundary. Only traverses until the first
    * part
    */
  private[this] def ignorePrelude[F[_]: Concurrent](
      b: Boundary,
      stream: Stream[F, Byte],
      limit: Int): Stream[F, Part[F]] = {
    val values = StartLineBytesN(b)

    def go(s: Stream[F, Byte], state: Int, strim: Stream[F, Byte]): Pull[F, Part[F], Unit] =
      if (state == values.length)
        pullParts[F](b, strim ++ s, limit)
      else
        s.pull.uncons.flatMap {
          case Some((chnk, rest)) =>
            val (ix, strim) = splitAndIgnorePrev(values, state, chnk)
            go(rest, ix, strim)
          case None =>
            Pull.raiseError[F](MalformedMessageBodyFailure("Malformed Malformed match"))
        }

    stream.pull.uncons.flatMap {
      case Some((chnk, strim)) =>
        val (ix, rest) = splitAndIgnorePrev(values, 0, chnk)
        go(strim, ix, rest)
      case None =>
        Pull.raiseError[F](MalformedMessageBodyFailure("Cannot parse empty stream"))
    }.stream
  }

  /** @param boundary
    * @param s
    * @param limit
    * @tparam F
    * @return
    */
  private def pullParts[F[_]: Concurrent](
      boundary: Boundary,
      s: Stream[F, Byte],
      limit: Int
  ): Pull[F, Part[F], Unit] = {
    val values = DoubleCRLFBytesN
    val expectedBytes = ExpectedBytesN(boundary)

    splitOrFinish[F](values, s, limit).flatMap { case (l, r) =>
      //We can abuse reference equality here for efficiency
      //Since `splitOrFinish` returns `empty` on a capped stream
      //However, we must have at least one part, so `splitOrFinish` on this function
      //Indicates an error
      if (r == streamEmpty)
        Pull.raiseError[F](MalformedMessageBodyFailure("Cannot parse empty stream"))
      else
        tailrecParts[F](boundary, l, r, expectedBytes, limit)
    }
  }

  private def tailrecParts[F[_]: Concurrent](
      b: Boundary,
      headerStream: Stream[F, Byte],
      rest: Stream[F, Byte],
      expectedBytes: Array[Byte],
      limit: Int): Pull[F, Part[F], Unit] =
    Pull
      .eval(parseHeaders(headerStream))
      .flatMap { hdrs =>
        splitHalf(expectedBytes, rest).flatMap { case (l, r) =>
          //We hit a boundary, but the rest of the stream is empty
          //and thus it's not a properly capped multipart body
          if (r == streamEmpty)
            Pull.raiseError[F](MalformedMessageBodyFailure("Part not terminated properly"))
          else
            Pull.output1(Part[F](hdrs, l)) >> splitOrFinish(DoubleCRLFBytesN, r, limit).flatMap {
              case (hdrStream, remaining) =>
                if (hdrStream == streamEmpty) //Empty returned if it worked fine
                  Pull.done
                else
                  tailrecParts[F](b, hdrStream, remaining, expectedBytes, limit)
            }
        }
      }

  /** Split a stream in half based on `values`,
    * but check if it is either double dash terminated (end of multipart).
    * SplitOrFinish also tracks a header limit size
    *
    * If it is, drain the epilogue and return the empty stream. if it is not,
    * split on the `values` and raise an error if we lack a match
    */
  private def splitOrFinish[F[_]: Concurrent](
      values: Array[Byte],
      stream: Stream[F, Byte],
      limit: Int): SplitStream[F] = {
    //Check if a particular chunk a final chunk, that is,
    //whether it's the boundary plus an extra "--", indicating it's
    //the last boundary
    def checkIfLast(c: Chunk[Byte], rest: Stream[F, Byte]): SplitStream[F] = {
      //precond: both c1 and c2 are nonempty chunks
      def checkTwoNonEmpty(
          c1: Chunk[Byte],
          c2: Chunk[Byte],
          remaining: Stream[F, Byte]): SplitStream[F] =
        if (c1(0) == dashByte && c2(0) == dashByte)
          // Drain the multipart epilogue.
          Pull.eval(rest.compile.drain) *>
            Pull.pure((streamEmpty, streamEmpty))
        else {
          val (ix, l, r, add) =
            splitOnChunkLimited[F](
              values,
              0,
              Chunk.array(c1.toArray[Byte] ++ c2.toArray[Byte]),
              Stream.empty,
              Stream.empty)
          go(remaining, ix, l, r, add)
        }

      if (c.size == 1)
        rest.pull.uncons.flatMap {
          case Some((chnk, remaining)) =>
            checkTwoNonEmpty(c, chnk, remaining)
          case None =>
            Pull.raiseError[F](MalformedMessageBodyFailure("Malformed Multipart ending"))
        }
      else if (c(0) == dashByte && c(1) == dashByte)
        // Drain the multipart epilogue.
        Pull.eval(rest.compile.drain) *>
          Pull.pure((streamEmpty, streamEmpty))
      else {
        val (ix, l, r, add) =
          splitOnChunkLimited[F](values, 0, c, Stream.empty, Stream.empty)
        go(rest, ix, l, r, add)
      }
    }

    def go(
        s: Stream[F, Byte],
        state: Int,
        lacc: Stream[F, Byte],
        racc: Stream[F, Byte],
        limitCTR: Int): SplitStream[F] =
      if (limitCTR >= limit)
        Pull.raiseError[F](
          MalformedMessageBodyFailure(s"Part header was longer than $limit-byte limit"))
      else if (state == values.length)
        Pull.pure((lacc, racc ++ s))
      else
        s.pull.uncons.flatMap {
          case Some((chnk, str)) =>
            val (ix, l, r, add) = splitOnChunkLimited[F](values, state, chnk, lacc, racc)
            go(str, ix, l, r, limitCTR + add)
          case None =>
            Pull.raiseError[F](MalformedMessageBodyFailure("Invalid boundary - partial boundary"))
        }

    stream.pull.uncons.flatMap {
      case Some((chunk, rest)) =>
        checkIfLast(chunk, rest)
      case None =>
        Pull.raiseError[F](MalformedMessageBodyFailure("Invalid boundary - partial boundary"))
    }
  }

  /** Take the stream of headers separated by
    * double CRLF bytes and return the headers
    */
  private def parseHeaders[F[_]: Concurrent](strim: Stream[F, Byte]): F[Headers] = {
    def tailrecParse(s: Stream[F, Byte], headers: Headers): Pull[F, Headers, Unit] =
      splitHalf[F](CRLFBytesN, s).flatMap { case (l, r) =>
        l.through(fs2.text.utf8Decode[F])
          .fold("")(_ ++ _)
          .map { string =>
            val ix = string.indexOf(':')
            if (ix >= 0)
              headers.put(Header(string.substring(0, ix), string.substring(ix + 1).trim))
            else
              headers
          }
          .pull
          .echo >> r.pull.uncons.flatMap {
          case Some(_) =>
            tailrecParse(r, headers)
          case None =>
            Pull.done
        }
      }

    tailrecParse(strim, Headers.empty).stream.compile
      .fold(Headers.empty)(_ ++ _)
  }

  /** Spit our `Stream[F, Byte]` into two halves.
    * If we reach the end and the state is 0 (meaning we didn't match at all),
    * then we return the concatenated parts of the stream.
    *
    * This method _always_ caps
    */
  private def splitHalf[F[_]: Monad](
      values: Array[Byte],
      stream: Stream[F, Byte]): SplitStream[F] = {
    def go(
        s: Stream[F, Byte],
        state: Int,
        lacc: Stream[F, Byte],
        racc: Stream[F, Byte]): SplitStream[F] =
      if (state == values.length)
        Pull.pure((lacc, racc ++ s))
      else
        s.pull.uncons.flatMap {
          case Some((chnk, str)) =>
            val (ix, l, r) = splitOnChunk[F](values, state, chnk, lacc, racc)
            go(str, ix, l, r)
          case None =>
            //We got to the end, and matched on nothing.
            Pull.pure((lacc ++ racc, streamEmpty))
        }

    stream.pull.uncons.flatMap {
      case Some((chunk, rest)) =>
        val (ix, l, r) = splitOnChunk[F](values, 0, chunk, Stream.empty, Stream.empty)
        go(rest, ix, l, r)
      case None =>
        Pull.pure((streamEmpty, streamEmpty))
    }
  }

  /** Split a chunk in the case of a complete match:
    *
    * If it is a chunk that is between a partial match
    * (middleChunked), consider the prior partial match
    * as part of the data to emit.
    *
    * If it is a fully matched, fresh chunk (no carry over partial match),
    * emit everything until the match, and everything after the match.
    *
    * If it is the continuation of a partial match,
    * emit everything after the partial match.
    */
  private def splitCompleteLimited[F[_]](
      state: Int,
      middleChunked: Boolean,
      sti: Int,
      i: Int,
      acc: Stream[F, Byte],
      carry: Stream[F, Byte],
      c: Chunk[Byte]
  ): (Int, Stream[F, Byte], Stream[F, Byte], Int) =
    if (middleChunked)
      (
        sti,
        //Emit the partial match as well
        acc ++ carry ++ Stream.chunk(c.take(i - sti)),
        //Emit after the match
        Stream.chunk(c.drop(i)),
        state + i - sti)
    else
      (
        sti,
        acc, //block completes partial match, so do not emit carry
        Stream.chunk(c.drop(i)), //Emit everything after the match
        0)

  /** Split a chunk in the case of a partial match:
    *
    * If it is a chunk that is between a partial match
    * (middle chunked), the prior partial match is added to
    * the accumulator, and the current partial match is
    * considered to carry over.
    *
    * If it is a fresh chunk (no carry over partial match),
    * everything prior to the partial match is added to the accumulator,
    * and the partial match is considered the carry over.
    *
    * Else, if the whole block is a partial match,
    * add it to the carry over
    */
  private[http4s] def splitPartialLimited[F[_]](
      state: Int,
      middleChunked: Boolean,
      currState: Int,
      i: Int,
      acc: Stream[F, Byte],
      carry: Stream[F, Byte],
      c: Chunk[Byte]
  ): (Int, Stream[F, Byte], Stream[F, Byte], Int) = {
    val ixx = i - currState
    if (middleChunked) {
      val (lchunk, rchunk) = c.splitAt(ixx)
      (
        currState,
        acc ++ carry ++ Stream.chunk(lchunk), //Emit previous carry
        Stream.chunk(rchunk),
        state + ixx)
    } else
      //Whole thing is partial match
      (currState, acc, carry ++ Stream.chunk(c), 0)
  }

  private[http4s] def splitOnChunkLimited[F[_]](
      values: Array[Byte],
      state: Int,
      c: Chunk[Byte],
      acc: Stream[F, Byte],
      carry: Stream[F, Byte]): (Int, Stream[F, Byte], Stream[F, Byte], Int) = {
    var i = 0
    var currState = state
    val len = values.length
    while (currState < len && i < c.size) {
      if (c(i) == values(currState))
        currState += 1
      else if (c(i) == values(0))
        currState = 1
      else
        currState = 0
      i += 1
    }

    //It will only be zero if
    //the chunk matches from the very beginning,
    //since currstate can never be greater than
    //(i + state).
    val middleChunked = i + state - currState > 0

    if (currState == 0)
      (0, acc ++ carry ++ Stream.chunk(c), Stream.empty, i)
    else if (currState == len)
      splitCompleteLimited(state, middleChunked, currState, i, acc, carry, c)
    else
      splitPartialLimited(state, middleChunked, currState, i, acc, carry, c)
  }

  ////////////////////////////////////////////////////////////
  // File writing encoder
  ///////////////////////////////////////////////////////////

  /** Same as the other streamed parsing, except
    * after a particular size, it buffers on a File.
    */
  def parseStreamedFile[F[_]: Concurrent: Files](
      boundary: Boundary,
      limit: Int = 1024,
      maxSizeBeforeWrite: Int = 52428800,
      maxParts: Int = 20,
      failOnLimit: Boolean = false): Pipe[F, Byte, Multipart[F]] = { st =>
    ignorePreludeFileStream[F](boundary, st, limit, maxSizeBeforeWrite, maxParts, failOnLimit)
      .fold(Vector.empty[Part[F]])(_ :+ _)
      .map(Multipart(_, boundary))
  }

  def parseToPartsStreamedFile[F[_]: Concurrent: Files](
      boundary: Boundary,
      limit: Int = 1024,
      maxSizeBeforeWrite: Int = 52428800,
      maxParts: Int = 20,
      failOnLimit: Boolean = false): Pipe[F, Byte, Part[F]] = { st =>
    ignorePreludeFileStream[F](boundary, st, limit, maxSizeBeforeWrite, maxParts, failOnLimit)
  }

  /** The first part of our streaming stages:
    *
    * Ignore the prelude and remove the first boundary. Only traverses until the first
    * part
    */
  private[this] def ignorePreludeFileStream[F[_]: Concurrent: Files](
      b: Boundary,
      stream: Stream[F, Byte],
      limit: Int,
      maxSizeBeforeWrite: Int,
      maxParts: Int,
      failOnLimit: Boolean): Stream[F, Part[F]] = {
    val values = StartLineBytesN(b)

    def go(s: Stream[F, Byte], state: Int, strim: Stream[F, Byte]): Pull[F, Part[F], Unit] =
      if (state == values.length)
        pullPartsFileStream[F](b, strim ++ s, limit, maxSizeBeforeWrite, maxParts, failOnLimit)
      else
        s.pull.uncons.flatMap {
          case Some((chnk, rest)) =>
            val (ix, strim) = splitAndIgnorePrev(values, state, chnk)
            go(rest, ix, strim)
          case None =>
            Pull.raiseError[F](MalformedMessageBodyFailure("Malformed Malformed match"))
        }

    stream.pull.uncons.flatMap {
      case Some((chnk, strim)) =>
        val (ix, rest) = splitAndIgnorePrev(values, 0, chnk)
        go(strim, ix, rest)
      case None =>
        Pull.raiseError[F](MalformedMessageBodyFailure("Cannot parse empty stream"))
    }.stream
  }

  /** @param boundary
    * @param s
    * @param limit
    * @tparam F
    * @return
    */
  private def pullPartsFileStream[F[_]: Concurrent: Files](
      boundary: Boundary,
      s: Stream[F, Byte],
      limit: Int,
      maxBeforeWrite: Int,
      maxParts: Int,
      failOnLimit: Boolean): Pull[F, Part[F], Unit] = {
    val values = DoubleCRLFBytesN
    val expectedBytes = ExpectedBytesN(boundary)

    splitOrFinish[F](values, s, limit).flatMap { case (l, r) =>
      //We can abuse reference equality here for efficiency
      //Since `splitOrFinish` returns `empty` on a capped stream
      //However, we must have at least one part, so `splitOrFinish` on this function
      //Indicates an error
      if (r == streamEmpty)
        Pull.raiseError[F](MalformedMessageBodyFailure("Cannot parse empty stream"))
      else
        tailrecPartsFileStream[F](
          boundary,
          l,
          r,
          expectedBytes,
          limit,
          maxBeforeWrite,
          1,
          maxParts,
          failOnLimit)
    }
  }

  private[this] def cleanupFileOption[F[_]: Files: MonadError[*[_], Throwable]](
      p: Option[Path]
  ): Pull[F, Nothing, Unit] =
    p match {
      case Some(path) =>
        Pull.eval(cleanupFile(path))

      case None =>
        PullUnit //Todo: Move to fs2
    }

  private[this] def cleanupFile[F[_]](
      path: Path
  )(implicit files: Files[F], F: MonadError[F, Throwable]): F[Unit] =
    files
      .delete(path)
      .handleErrorWith { err =>
        logger.error(err)("Caught error during file cleanup for multipart")
        //Swallow and report io exceptions in case
        F.unit
      }

  private[this] def tailrecPartsFileStream[F[_]: Concurrent: Files](
      b: Boundary,
      headerStream: Stream[F, Byte],
      rest: Stream[F, Byte],
      expectedBytes: Array[Byte],
      headerLimit: Int,
      maxBeforeWrite: Int,
      partsCounter: Int,
      partsLimit: Int,
      failOnLimit: Boolean): Pull[F, Part[F], Unit] =
    Pull
      .eval(parseHeaders(headerStream))
      .flatMap { hdrs =>
        splitWithFileStream(expectedBytes, rest, maxBeforeWrite).flatMap {
          case (partBody, rest, fileRef) =>
            //We hit a boundary, but the rest of the stream is empty
            //and thus it's not a properly capped multipart body
            if (rest == streamEmpty)
              cleanupFileOption[F](fileRef) >> Pull.raiseError[F](
                MalformedMessageBodyFailure("Part not terminated properly"))
            else
              Pull.output1(makePart(hdrs, partBody, fileRef)) >> splitOrFinish(
                DoubleCRLFBytesN,
                rest,
                headerLimit)
                .flatMap { case (hdrStream, remaining) =>
                  if (hdrStream == streamEmpty) //Empty returned if it worked fine
                    Pull.done
                  else if (partsCounter >= partsLimit)
                    if (failOnLimit)
                      Pull.raiseError[F](MalformedMessageBodyFailure("Parts limit exceeded"))
                    else
                      Pull.done
                  else
                    tailrecPartsFileStream[F](
                      b,
                      hdrStream,
                      remaining,
                      expectedBytes,
                      headerLimit,
                      maxBeforeWrite,
                      partsCounter + 1,
                      partsLimit,
                      failOnLimit)
                      .handleErrorWith(e => cleanupFileOption(fileRef) >> Pull.raiseError[F](e))
                }
        }
      }

  private[this] def makePart[F[_]: Applicative](
      hdrs: Headers,
      body: Stream[F, Byte],
      path: Option[Path]
  )(implicit files: Files[F]): Part[F] =
    path match {
      case Some(p) => Part(hdrs, body.onFinalizeWeak(files.delete(p)))
      case None => Part(hdrs, body)
    }

  /** Split the stream on `values`, but when
    */
  private def splitWithFileStream[F[_]: Concurrent: Files](
      values: Array[Byte],
      stream: Stream[F, Byte],
      maxBeforeWrite: Int): SplitFileStream[F] = {
    def streamAndWrite(
        s: Stream[F, Byte],
        state: Int,
        lacc: Stream[F, Byte],
        racc: Stream[F, Byte],
        limitCTR: Int,
        fileRef: Path): SplitFileStream[F] =
      if (state == values.length)
        Pull.eval(
          lacc
            .through(Files[F].writeAll(fileRef, List(StandardOpenOption.APPEND)))
            .compile
            .drain) >> Pull.pure(
          (Files[F].readAll(fileRef, maxBeforeWrite), racc ++ s, Some(fileRef)))
      else if (limitCTR >= maxBeforeWrite)
        Pull.eval(
          lacc
            .through(Files[F].writeAll(fileRef, List(StandardOpenOption.APPEND)))
            .compile
            .drain) >> streamAndWrite(s, state, Stream.empty, racc, 0, fileRef)
      else
        s.pull.uncons.flatMap {
          case Some((chnk, str)) =>
            val (ix, l, r, add) = splitOnChunkLimited[F](values, state, chnk, lacc, racc)
            streamAndWrite(str, ix, l, r, limitCTR + add, fileRef)
          case None =>
            Pull.eval(Files[F].delete(fileRef).attempt) >> Pull.raiseError[F](
              MalformedMessageBodyFailure("Invalid boundary - partial boundary"))
        }

    def go(
        s: Stream[F, Byte],
        state: Int,
        lacc: Stream[F, Byte],
        racc: Stream[F, Byte],
        limitCTR: Int): SplitFileStream[F] =
      if (limitCTR >= maxBeforeWrite)
        Pull
          .eval(Files[F].tempFile(None, "", "").allocated)
          .flatMap { case (path, cleanup) =>
            (
              for {
                _ <- Pull.eval(lacc.through(Files[F].writeAll(path)).compile.drain)
                split <- streamAndWrite(s, state, Stream.empty, racc, 0, path)
              } yield split
            ).onError { case _ => Pull.eval(cleanup) }
          }
      else if (state == values.length)
        Pull.pure((lacc, racc ++ s, None))
      else
        s.pull.uncons.flatMap {
          case Some((chnk, str)) =>
            val (ix, l, r, add) = splitOnChunkLimited[F](values, state, chnk, lacc, racc)
            go(str, ix, l, r, limitCTR + add)
          case None =>
            Pull.raiseError[F](MalformedMessageBodyFailure("Invalid boundary - partial boundary"))
        }

    stream.pull.uncons.flatMap {
      case Some((chunk, rest)) =>
        val (ix, l, r, add) =
          splitOnChunkLimited[F](values, 0, chunk, Stream.empty, Stream.empty)
        go(rest, ix, l, r, add)
      case None =>
        Pull.raiseError[F](MalformedMessageBodyFailure("Invalid boundary - partial boundary"))
    }
  }
}
