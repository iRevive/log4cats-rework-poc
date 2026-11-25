package org.typelevel.log4cats.experimental.pipeline

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all._
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.backend.{Backend, BoundBackend, EncodedEvent, EventMeta}
import org.typelevel.log4cats.experimental.context.{ContextSource, LogContext}
import org.typelevel.log4cats.experimental.encoding.{Encoder, ValueEncoding}
import org.typelevel.log4cats.experimental.value.{Value, ValueBackend}
import org.typelevel.log4cats.experimental.{Level, Scope}
import org.typelevel.vault.{Key, Vault}

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
        captures.update(_ + 1).as(Vault.empty),
        (_, _) => IO.pure(true)
      )
      plan   = Pipeline.fromBackend[IO](Resource.eval(acquisitions.update(_ + 1).as[Backend[IO]](backend)))
      source = ContextSource(contexts.update(_ + 1).as(LogContext()))
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
      backend = ValueBackend[IO](_ => events.update(_ + 1), IO.pure(Vault.empty), (_, _) => IO.pure(true))
      result <- Pipeline.runtimeResource(output(backend)).use { runtime =>
        val logger = runtime.fromNameUnsafe("callback-failure")
        val effect = logger.log(Level.Info, None, _ => throw callbackFailure)
        effect.attempt
      }
      failingSource = new ContextSource[IO] {
        def current: IO[LogContext] = throw contextFailure
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

  test("backend captures merge in order before acceptance and rejected outputs never encode") {
    for {
      shared     <- Key.newKey[IO, String]
      firstOnly  <- Key.newKey[IO, Int]
      secondOnly <- Key.newKey[IO, Boolean]
      steps      <- Ref.of[IO, Vector[String]](Vector.empty)
      events     <- Ref.of[IO, Vector[EncodedEvent[Value]]](Vector.empty)
      firstVault  = Vault.empty.insert(shared, "first").insert(firstOnly, 1)
      secondVault = Vault.empty.insert(shared, "second").insert(secondOnly, true)
      check = (name: String, meta: EventMeta, vault: Vault) =>
        steps.update(_ :+ name) *> IO {
          assertEquals(vault.lookup(shared), Some("second"))
          assertEquals(vault.lookup(firstOnly), Some(1))
          assertEquals(vault.lookup(secondOnly), Some(true))
          assertEquals(meta.level, Level.Warn)
          assertEquals(meta.eventName, Some("captured.event"))
        }
      first = ValueBackend[IO](
        e => steps.update(_ :+ "emit") *> events.update(_ :+ e),
        steps.update(_ :+ "capture-first").as(firstVault),
        (m, v) => check("accept-first", m, v).as(true)
      )
      second = new BoundBackend[IO] {
        type Repr = Value
        def encoding: ValueEncoding[Value]               = throw new AssertionError("rejected encoding accessed")
        def capture(meta: EventMeta): IO[Vault]          = steps.update(_ :+ "capture-second").as(secondVault)
        def accepts(m: EventMeta, v: Vault): IO[Boolean] = check("accept-second", m, v).as(false)
        def emit(e: EncodedEvent[Value]): IO[Unit]       = IO.raiseError(new AssertionError("rejected output emitted"))
      }
      source = ContextSource(
        steps.update(_ :+ "ambient").as(LogContext(attachments = Vault.empty.insert(shared, "ambient")))
      )
      _ <- Pipeline.runtimeResource(output(first) ++ output(Backend.constant(second)), source).use { runtime =>
        val logger = runtime.fromNameUnsafe("captures")
        logger.log(Level.Warn, Some("captured.event"), _.body("ok"))
      }
      recordedSteps <- steps.get
      recorded      <- events.get
    } yield {
      assertEquals(
        recordedSteps,
        Vector("ambient", "capture-first", "capture-second", "accept-first", "accept-second", "emit")
      )
      assertEquals(recorded.size, 1)
      assertEquals(recorded.head.fields, Value.Obj(Map.empty): Value)
      assertEquals(recorded.head.attachments.lookup(shared), Some("second"))
    }
  }

  test("contextual rejection by every backend skips the writer and encoding") {
    for {
      captures <- Ref.of[IO, Int](0)
      rejected = new BoundBackend[IO] {
        type Repr = Value
        def encoding: ValueEncoding[Value]                      = throw new AssertionError("rejected encoding accessed")
        def capture(meta: EventMeta): IO[Vault]                 = captures.update(_ + 1).as(Vault.empty)
        def accepts(meta: EventMeta, vault: Vault): IO[Boolean] = IO.pure(false)
        def emit(event: EncodedEvent[Value]): IO[Unit] = IO.raiseError(new AssertionError("rejected output emitted"))
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
      first  = ValueBackend[IO](_ => emitted.update(_ :+ "first"), IO.pure(Vault.empty), (_, _) => IO.pure(true))
      broken = ValueBackend[IO](_ => throw failure, IO.pure(Vault.empty), (_, _) => IO.pure(true))
      later  = ValueBackend[IO](_ => emitted.update(_ :+ "later"), IO.pure(Vault.empty), (_, _) => IO.pure(true))
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
        IO.pure(Vault.empty),
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
