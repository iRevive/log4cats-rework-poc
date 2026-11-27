package org.typelevel.log4cats.experimental

import cats.effect.{IO, IOApp}
import org.typelevel.log4cats.experimental.slf4j.Slf4j

object Slf4jExample extends IOApp.Simple {
  final class Checkout(logger: Logger[IO]) {
    def complete: IO[Unit] = logger.infoEvent("checkout.completed") {
      Event.message("checkout accepted").withMdc("request.id", "req-42").withField("accepted", true)
    }
  }

  val factory = Slf4j.factory[IO]

  def run: IO[Unit] =
    for {
      logger <- factory.fromClass(classOf[Checkout])
      _      <- new Checkout(logger).complete
    } yield ()
}
