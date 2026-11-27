package org.typelevel.log4cats.experimental.pipeline

import cats.effect.{Deferred, IO, IOLocal, Ref, Resource}
import cats.syntax.all._
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.backend.{Backend, BoundBackend, EncodedEvent, EventMeta}
import org.typelevel.log4cats.experimental.context.ContextSource
import org.typelevel.log4cats.experimental.encoding.{Encoder, ValueEncoding}
import org.typelevel.log4cats.experimental.value.{Value, ValueBackend}
import org.typelevel.log4cats.experimental.{Field, Fields, Level, Scope}

final class DeliveryContractSuite extends CatsEffectSuite {
  private def output(backend: Backend[IO]): Pipeline[IO] =
    Pipeline.fromBackend[IO](Resource.pure[IO, Backend[IO]](backend))

  test("prepared replay retains the occurrence without reacquiring, capturing, constructing or encoding") {
    var constructions = 0
    var encodes       = 0
    var message       = "first"
    val encoder = new Encoder[String] {
      def encode[V](value: String)(out: ValueEncoding[V]): V = {
        encodes += 1
        out.text(value)
      }
    }
    for {
      acquisitions <- Ref.of[IO, Int](0)
      contexts     <- Ref.of[IO, Int](0)
      captures     <- Ref.of[IO, Int](0)
      events       <- Ref.of[IO, Vector[EncodedEvent[Value]]](Vector.empty)
      backend = ValueBackend[IO](
        e => events.update(_ :+ e),
        captures.update(_ + 1).as(()),
        (_, _) => IO.pure(true)
      )
      plan   = Pipeline.fromBackend[IO](Resource.eval(acquisitions.update(_ + 1).as[Backend[IO]](backend)))
      source = ContextSource(contexts.update(_ + 1).as(Fields.empty))
      _ <- Pipeline.runtimeResource(plan, source).use { runtime =>
        val prepare = runtime.prepare(Scope("replay"), Level.Info, None) { writer =>
          constructions += 1
          writer.body(message)(encoder)
        }
        for {
          before <- (contexts.get, captures.get, events.get).tupled
          _ <- IO {
            assertEquals(before, (0, 0, Vector.empty[EncodedEvent[Value]]))
            assertEquals((constructions, encodes), (0, 0))
          }
          delivery <- prepare
          _        <- IO { message = "second" }
          _        <- delivery.deliver *> delivery.deliver
          counts   <- (acquisitions.get, contexts.get, captures.get).tupled
          _ <- IO {
            assertEquals(counts, (1, 1, 1))
            assertEquals((constructions, encodes), (1, 1))
          }
          next <- prepare
          _    <- next.deliver
        } yield ()
      }
      counts   <- (acquisitions.get, contexts.get, captures.get).tupled
      recorded <- events.get
    } yield {
      assertEquals(counts, (1, 2, 2))
      assertEquals((constructions, encodes), (2, 2))
      assertEquals(recorded.map(_.body), Vector[Value](Value.Text("first"), Value.Text("first"), Value.Text("second")))
      assertEquals(recorded(0).meta, recorded(1).meta)
    }
  }

  test("callback and context construction failures are suspended before any emission") {
    val callbackFailure = new IllegalArgumentException("callback")
    val contextFailure  = new IllegalStateException("context")
    for {
      events <- Ref.of[IO, Int](0)
      backend = ValueBackend[IO](_ => events.update(_ + 1), IO.pure(()), (_, _) => IO.pure(true))
      result <- Pipeline.runtimeResource(output(backend)).use { runtime =>
        val logger = runtime.fromNameUnsafe("callback-failure")
        val effect = logger.log(Level.Info, None, _ => throw callbackFailure)
        effect.attempt
      }
      failingSource = new ContextSource[IO] {
        def current: IO[Fields] = throw contextFailure
      }
      contextResult <- Pipeline.runtimeResource(output(backend), failingSource).use { runtime =>
        val logger = runtime.fromNameUnsafe("context-failure")
        logger.info("unreachable").attempt
      }
      count <- events.get
    } yield {
      assertEquals(result, Left(callbackFailure): Either[Throwable, Unit])
      assertEquals(contextResult, Left(contextFailure): Either[Throwable, Unit])
      assertEquals(count, 0)
    }
  }

  test("backend state stays typed and private while projected fields merge before acceptance") {
    for {
      steps  <- Ref.of[IO, Vector[String]](Vector.empty)
      events <- Ref.of[IO, Vector[EncodedEvent[Value]]](Vector.empty)
      first = new BoundBackend[IO] {
        type Repr    = Value
        type Context = String
        val encoding: ValueEncoding[Value]                  = ValueEncoding.value
        def capture(meta: EventMeta): IO[String]            = steps.update(_ :+ "capture-first").as("first")
        override def contextFields(context: String): Fields = Fields(Field("capture", context))
        def accepts(meta: EventMeta, context: String): IO[Boolean] =
          steps.update(_ :+ "accept-first") *> IO { assertEquals(context, "first"); true }
        def emit(event: EncodedEvent[Value], context: String): IO[Unit] =
          IO(assertEquals(context, "first")) *> steps.update(_ :+ "emit") *> events.update(_ :+ event)
      }
      second = new BoundBackend[IO] {
        type Repr    = Value
        type Context = Int
        def encoding: ValueEncoding[Value]               = throw new AssertionError("rejected encoding accessed")
        def capture(meta: EventMeta): IO[Int]            = steps.update(_ :+ "capture-second").as(2)
        override def contextFields(context: Int): Fields = Fields(Field("capture", "second"), Field("count", context))
        def accepts(meta: EventMeta, context: Int): IO[Boolean] =
          steps.update(_ :+ "accept-second") *> IO { assertEquals(context, 2); false }
        def emit(event: EncodedEvent[Value], context: Int): IO[Unit] =
          IO.raiseError(new AssertionError("rejected output emitted"))
      }
      source = ContextSource(steps.update(_ :+ "ambient").as(Fields(Field("capture", "ambient"))))
      _ <- Pipeline.runtimeResource(output(Backend.constant(first)) ++ output(Backend.constant(second)), source).use {
        runtime =>
          runtime.fromNameUnsafe("captures").log(Level.Warn, Some("captured.event"), _.body("ok"))
      }
      recordedSteps <- steps.get
      recorded      <- events.get
    } yield {
      assertEquals(
        recordedSteps,
        Vector("ambient", "capture-first", "capture-second", "accept-first", "accept-second", "emit")
      )
      assertEquals(recorded.size, 1)
      assertEquals(
        recorded.head.fields,
        Value.Obj(Map("capture" -> Value.Text("second"), "count" -> Value.Int64(2))): Value
      )
    }
  }

  test("typed captures and their projected fields survive queued prepared replay without recapture") {
    for {
      local    <- IOLocal("worker")
      captures <- Ref.of[IO, Int](0)
      emitted  <- Ref.of[IO, Vector[(String, String, Value)]](Vector.empty)
      backend = new BoundBackend[IO] {
        type Repr    = Value
        type Context = String
        val encoding: ValueEncoding[Value]                         = ValueEncoding.value
        def capture(meta: EventMeta): IO[String]                   = captures.update(_ + 1) *> local.get
        override def contextFields(context: String): Fields        = Fields(Field("trace", context))
        def accepts(meta: EventMeta, context: String): IO[Boolean] = IO.pure(true)
        def emit(event: EncodedEvent[Value], context: String): IO[Unit] =
          local.get.flatMap(actual => emitted.update(_ :+ ((context, actual, event.fields))))
      }
      _ <- Pipeline.runtimeResource(output(Backend.constant(backend)).async(2)).use { runtime =>
        for {
          _        <- local.set("producer")
          prepared <- runtime.prepare(Scope("typed"), Level.Info, None)(_.body("record"))
          _        <- local.set("consumer")
          _        <- prepared.deliver *> prepared.deliver
          _        <- runtime.flush
        } yield ()
      }
      count   <- captures.get
      records <- emitted.get
    } yield {
      assertEquals(count, 1)
      assertEquals(
        records,
        Vector.fill(2)(("producer", "worker", Value.Obj(Map("trace" -> Value.Text("producer"))): Value))
      )
    }
  }

  test("contextual rejection by every backend skips the writer and encoding") {
    for {
      captures <- Ref.of[IO, Int](0)
      rejected = new BoundBackend[IO] {
        type Context = Unit
        type Repr    = Value
        def encoding: ValueEncoding[Value]     = throw new AssertionError("rejected encoding accessed")
        def capture(meta: EventMeta): IO[Unit] = captures.update(_ + 1).as(())
        def accepts(meta: EventMeta, context: Unit): IO[Boolean] = IO.pure(false)
        def emit(event: EncodedEvent[Value], context: Unit): IO[Unit] =
          IO.raiseError(new AssertionError("rejected output emitted"))
      }
      _ <- Pipeline.runtimeResource(output(Backend.constant(rejected))).use { runtime =>
        val logger = runtime.fromNameUnsafe("rejected")
        logger.log(Level.Info, None, _ => throw new AssertionError("rejected writer invoked"))
      }
      count <- captures.get
    } yield assertEquals(count, 1)
  }

  test("delivery is sequential and fail-fast, retaining earlier emissions") {
    val failure = new RuntimeException("emit failed")
    for {
      emitted <- Ref.of[IO, Vector[String]](Vector.empty)
      first  = ValueBackend[IO](_ => emitted.update(_ :+ "first"), IO.pure(()), (_, _) => IO.pure(true))
      broken = ValueBackend[IO](_ => throw failure, IO.pure(()), (_, _) => IO.pure(true))
      later  = ValueBackend[IO](_ => emitted.update(_ :+ "later"), IO.pure(()), (_, _) => IO.pure(true))
      result <- Pipeline
        .runtimeResource(output(first) ++ output(broken) ++ output(later))
        .use { runtime =>
          val logger = runtime.fromNameUnsafe("fail-fast")
          logger.info("event")
        }
        .attempt
      seen <- emitted.get
    } yield {
      assertEquals(result, Left(failure): Either[Throwable, Unit])
      assertEquals(seen, Vector("first"))
    }
  }

  test("cancellation during emission propagates to the delivery fiber") {
    for {
      started <- Deferred[IO, Unit]
      backend = ValueBackend[IO](
        _ => started.complete(()).void *> IO.never[Unit],
        IO.pure(()),
        (_, _) => IO.pure(true)
      )
      _ <- Pipeline.runtimeResource(output(backend)).use { runtime =>
        val logger = runtime.fromNameUnsafe("cancel")
        for {
          fiber  <- logger.info("wait").start
          _      <- started.get
          _      <- fiber.cancel
          result <- fiber.join
        } yield assert(result.isCanceled)
      }
    } yield ()
  }
}
