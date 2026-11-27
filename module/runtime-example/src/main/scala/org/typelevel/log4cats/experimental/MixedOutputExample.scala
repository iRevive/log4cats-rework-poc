package org.typelevel.log4cats.experimental

import cats.effect.{IO, IOApp, Resource}
import cats.syntax.all._
import org.typelevel.log4cats.experimental.context.ContextSource
import org.typelevel.log4cats.experimental.encoding.{Encoder, ValueEncoding}
import org.typelevel.log4cats.experimental.otel.OtelBackend
import org.typelevel.log4cats.experimental.pipeline.Pipeline
import org.typelevel.log4cats.experimental.slf4j.Slf4jBackend
import org.typelevel.otel4s.oteljava.testkit.logs.LogsTestkit

object MixedOutputExample extends IOApp.Simple {
  final case class Customer(id: String, retries: Int)

  implicit val customerEncoder: Encoder[Customer] = new Encoder[Customer] {
    def encode[V](customer: Customer)(out: ValueEncoding[V]): V = {
      val b = out.obj()
      b.put("id", out.text(customer.id))
      b.put("retries", out.int64(customer.retries.toLong))
      b.result()
    }
  }

  final class Checkout(logger: Logger[IO]) {
    def complete(customer: Customer): IO[Unit] =
      logger.debug("telemetry-only diagnostic") *>
        logger.infoEvent("checkout.completed") {
          Event.body(customer).withField("accepted", true)
        }
  }

  def run: IO[Unit] = LogsTestkit.inMemory[IO]().use { kit =>
    val primary = Pipeline
      .fromBackend[IO] {
        Resource.eval(Slf4jBackend.acquire[IO])
      }
      .atLeast(Level.Info)
    val telemetry = Pipeline
      .fromBackend[IO] {
        OtelBackend.resource(kit.loggerProvider)
      }
      .atLeast(Level.Debug)
    val request = ContextSource.fromMdc(IO.pure(Mdc("request.id" -> "req-42")))
    val outputs = (primary ++ telemetry)
      .withFields(Fields(Field("service", "checkout")))
      .withContextSource(request)

    Pipeline
      .resource(outputs)
      .use { factory =>
        for {
          logger   <- factory.fromName("example.checkout")
          _        <- new Checkout(logger).complete(Customer("customer-7", 2))
          exported <- kit.finishedLogs
          _        <- IO.println("Exported " + exported.size + " records to the in-memory OTel testkit")
        } yield ()
      }
  }
}
