package org.typelevel.log4cats.experimental

import cats.effect.{IO, IOApp, Resource}
import cats.syntax.all._
import org.typelevel.log4cats.experimental.backend.Backend
import org.typelevel.log4cats.experimental.console.ConsoleBackend
import org.typelevel.log4cats.experimental.pipeline.Pipeline

object AsyncOutputExample extends IOApp.Simple {
  def run: IO[Unit] = {
    val output = Pipeline
      .fromBackend[IO](Resource.pure[IO, Backend[IO]](ConsoleBackend.stdout[IO]))
      .async(16)

    Pipeline.runtimeResource(output).use { runtime =>
      val logger = runtime.getLoggerFromName("async.checkout")
      logger.info("checkout accepted") *> runtime.flush
    }
  }
}
