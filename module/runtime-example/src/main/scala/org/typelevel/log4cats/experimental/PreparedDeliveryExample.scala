package org.typelevel.log4cats.experimental

import cats.effect.{IO, IOApp, Resource}
import cats.syntax.all._
import org.typelevel.log4cats.experimental.backend.Backend
import org.typelevel.log4cats.experimental.console.ConsoleBackend
import org.typelevel.log4cats.experimental.context.ContextSource
import org.typelevel.log4cats.experimental.pipeline.Pipeline

object PreparedDeliveryExample extends IOApp.Simple {
  def run: IO[Unit] = {
    val output  = Pipeline.fromBackend[IO](Resource.pure[IO, Backend[IO]](ConsoleBackend.stdout[IO]))
    val request = ContextSource(IO.pure(Fields(Field("request.id", "req-42"))))

    Pipeline.runtimeResource(output, request).use { runtime =>
      for {
        delivery <- runtime.prepare(Scope("prepared.checkout"), Level.Warn, Some("checkout.review")) { writer =>
          writer.body("manual review required")
          writer.field("customer", "customer-7")
        }
        _ <- delivery.deliver.start.flatMap(_.joinWithNever)
      } yield ()
    }
  }
}
