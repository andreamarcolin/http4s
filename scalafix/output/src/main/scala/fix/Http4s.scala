package fix
import cats.effect.IO
import org.http4s.{HttpRoutes, MediaType, Request, Response}
import org.http4s.dsl.io._
import org.http4s.{ResponseCookie => Cookie}

object Http4s {
  // Add code that needs fixing here.

  def service(): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root => Ok().withEntity()
  }

  def serviceWithoutExplicitType(): HttpRoutes[IO] = HttpRoutes.of {
    case req @ GET -> Root => Ok()
  }

  val requestWithBody: Request[IO] = Request().withEntity("hello")
  def responseWithBody: IO[Response[IO]] = Ok().withEntity("world")

  val x = MediaType.application.`atom+xml`
}
