package consumer

import cats.effect.{IO, Ref, Resource, Sync}
import cats.syntax.all._
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.backend.{
  Backend,
  BoundBackend,
  CapturedEvent,
  Delivery,
  EncodedEvent,
  EventMeta
}
import org.typelevel.log4cats.experimental.encoding.ValueEncoding
import org.typelevel.log4cats.experimental.pipeline.Pipeline
import org.typelevel.log4cats.experimental.value.{Value, ValueBackend}
import org.typelevel.log4cats.experimental.{Level, Scope}
import org.typelevel.vault.Vault

final class BackendPreparationSuite extends CatsEffectSuite {
  test("an external backend constructs a prepared delivery and replays only its emission") {
    for {
      preparations <- Ref.of[IO, Int](0)
      emitted      <- Ref.of[IO, Vector[Value]](Vector.empty)
      backend = new BoundBackend[IO] {
        type Repr = Value
        def encoding: ValueEncoding[Value]                            = ValueEncoding.value
        def capture(meta: EventMeta): IO[Vault]                       = IO.pure(Vault.empty)
        def accepts(meta: EventMeta, attachments: Vault): IO[Boolean] = IO.pure(true)
        def emit(event: EncodedEvent[Value]): IO[Unit] = IO.raiseError(new AssertionError("default emission used"))
        override protected def prepareOutput(event: CapturedEvent)(implicit F: Sync[IO]): IO[Delivery[IO]] =
          preparations.update(_ + 1) *> F.delay {
            val body = event.body.encode(encoding)
            Delivery(emitted.update(_ :+ body))
          }
      }
      plan = Pipeline.fromBackend[IO](Resource.pure[IO, Backend[IO]](Backend.constant(backend)))
      _ <- Pipeline.runtimeResource(plan).use { runtime =>
        for {
          delivery <- runtime.prepare(Scope("external"), Level.Info, None)(_.body("prepared"))
          before   <- emitted.get
          _        <- IO(assertEquals(before, Vector.empty[Value]))
          _        <- delivery.deliver *> delivery.deliver
        } yield ()
      }
      count   <- preparations.get
      records <- emitted.get
    } yield {
      assertEquals(count, 1)
      assertEquals(records, Vector[Value](Value.Text("prepared"), Value.Text("prepared")))
    }
  }

  test("an external preparation failure prevents emission by an earlier sibling") {
    val failure = new RuntimeException("custom preparation failed")
    for {
      emitted <- Ref.of[IO, Int](0)
      first = ValueBackend[IO](_ => emitted.update(_ + 1), IO.pure(Vault.empty), (_, _) => IO.pure(true))
      broken = new BoundBackend[IO] {
        type Repr = Value
        def encoding: ValueEncoding[Value]                            = ValueEncoding.value
        def capture(meta: EventMeta): IO[Vault]                       = IO.pure(Vault.empty)
        def accepts(meta: EventMeta, attachments: Vault): IO[Boolean] = IO.pure(true)
        def emit(event: EncodedEvent[Value]): IO[Unit]                = emitted.update(_ + 1)
        override protected def prepareOutput(event: CapturedEvent)(implicit F: Sync[IO]): IO[Delivery[IO]] =
          F.raiseError(failure)
      }
      plan = Pipeline.fromBackend[IO](Resource.pure[IO, Backend[IO]](first)) ++
        Pipeline.fromBackend[IO](Resource.pure[IO, Backend[IO]](Backend.constant(broken)))
      result <- Pipeline.runtimeResource(plan).use(_.fromNameUnsafe("failure").info("event")).attempt
      count  <- emitted.get
    } yield {
      assertEquals(result, Left(failure): Either[Throwable, Unit])
      assertEquals(count, 0)
    }
  }
}
