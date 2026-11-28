package org.typelevel.log4cats.experimental.context

import cats.data.Kleisli
import cats.effect.{IO, Ref, Resource}
import cats.implicits._
import cats.mtl.{Ask, Local}
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.backend.{Backend, EncodedEvent}
import org.typelevel.log4cats.experimental.pipeline.Pipeline
import org.typelevel.log4cats.experimental.value.{Value, ValueBackend}
import org.typelevel.log4cats.experimental.{Event, Field, Fields, Mdc}

final class ContextSuite extends CatsEffectSuite {
  private type Reader[A] = Kleisli[IO, Fields, A]

  private val readerLocal: Local[Reader, Fields] = implicitly[Local[Reader, Fields]]

  test("Ask supplies ambient fields and Local scopes right-biased nested fields") {
    val ask: Ask[Reader, Fields] = readerLocal
    val source                   = ContextSource.fromAsk[Reader](ask)

    val nested: Reader[(Fields, Fields, Fields)] = for {
      before <- source.current.map(_.fields)
      inner <- LoggingContext.withFields[Reader, Fields](Fields(Field("k", "inner"))) {
        source.current.map(_.fields)
      }
      after <- source.current.map(_.fields)
    } yield (before, inner, after)

    val scoped = LoggingContext.withFields[Reader, (Fields, Fields, Fields)](
      Fields(Field("k", "outer"), Field("outer", true))
    )(nested)

    val result = scoped.run(Fields(Field("k", "ambient"))).map { case (before, inner, after) =>
      (
        before.encode(org.typelevel.log4cats.experimental.encoding.ValueEncoding.value),
        inner.encode(org.typelevel.log4cats.experimental.encoding.ValueEncoding.value),
        after.encode(org.typelevel.log4cats.experimental.encoding.ValueEncoding.value)
      )
    }

    result.map { values =>
      val outer = org.typelevel.log4cats.experimental.value.Value.Obj(
        Map(
          "k"     -> org.typelevel.log4cats.experimental.value.Value.Text("outer"),
          "outer" -> org.typelevel.log4cats.experimental.value.Value.Bool(true)
        )
      )
      val inner = org.typelevel.log4cats.experimental.value.Value.Obj(
        Map(
          "k"     -> org.typelevel.log4cats.experimental.value.Value.Text("inner"),
          "outer" -> org.typelevel.log4cats.experimental.value.Value.Bool(true)
        )
      )
      assertEquals(values, (outer, inner, outer))
    }
  }

  test("Local scope restores after an effect failure") {
    val failed: Reader[Fields] = LoggingContext
      .withFields[Reader, Fields](Fields(Field("scope", "temporary"))) {
        Kleisli.liftF[IO, Fields, Fields](IO.raiseError(new RuntimeException("expected")))
      }
    val checked = failed.attempt.flatMap { result =>
      readerLocal.ask[Fields].map(fields => (result, fields))
    }
    checked.run(Fields.empty).map { case (result, restored) =>
      assert(result.isLeft)
      assert(restored.isEmpty)
    }
  }

  test("a Reader-based pipeline consumes MTL context without an IOLocal") {
    for {
      events <- Ref.of[IO, Vector[EncodedEvent[Value]]](Vector.empty)
      backend = ValueBackend[Reader](
        event => Kleisli.liftF(events.update(_ :+ event)),
        Kleisli.pure[IO, Fields, Unit](()),
        (_, _) => Kleisli.pure[IO, Fields, Boolean](true)
      )
      plan = Pipeline
        .fromBackend[Reader](Resource.pure[Reader, Backend[Reader]](backend))
        .withFields(Fields(Field("static", true)))
      source = ContextSource.fromAsk[Reader](readerLocal)
      _ <- Pipeline
        .resource(plan, source)
        .use { factory =>
          val logger = factory.getLoggerFromName("reader")
          LoggingContext.withFields[Reader, Unit](Fields(Field("scope", "nested"))) {
            logger.infoEvent(Event.message("event").withField("call", true))
          }
        }
        .run(Fields(Field("request", "request-1")))
      recorded <- events.get
    } yield assertEquals(
      recorded.head.fields,
      Value.Obj(
        Map(
          "static"  -> Value.Bool(true),
          "scope"   -> Value.Text("nested"),
          "request" -> Value.Text("request-1"),
          "call"    -> Value.Bool(true)
        )
      ): Value
    )
  }

  test("two-channel fiber scopes restore on cancellation and remain isolated from siblings") {
    import cats.effect.Deferred
    import org.typelevel.log4cats.experimental.Mdc
    for {
      scoped   <- ScopedContext.create.widen[LogScope[IO]]
      ready    <- Deferred[IO, Unit]
      restored <- Deferred[IO, LogContext]
      outer = LogContext(Mdc("id" -> "outer"), Fields(Field("id", 1)))
      _ <- scoped.scope(outer) {
        for {
          fiber <- scoped
            .withMdc(Mdc("id" -> "child"))(ready.complete(()) *> IO.never)
            .onCancel(scoped.current.flatMap(restored.complete).void)
            .start
          _       <- ready.get
          sibling <- scoped.current
          _       <- fiber.cancel
          after   <- restored.get
        } yield {
          assertEquals(sibling.mdc.values, Map("id" -> "outer"))
          assertEquals(after.mdc.values, Map("id" -> "outer"))
          assertEquals(
            after.fields.encode(org.typelevel.log4cats.experimental.encoding.ValueEncoding.value),
            Value.Obj(Map("id" -> Value.Int64(1)))
          )
        }
      }
      after <- scoped.current
    } yield {
      assert(after.mdc.isEmpty)
      assert(after.fields.isEmpty)
    }
  }

  test("IO LogScope restores both channels after failure and reused scopes read the current parent") {
    val failure = new RuntimeException("scope failed")
    for {
      scope <- ScopedContext.create.widen[LogScope[IO]]
      nested = scope.withMdc(Mdc("id" -> "nested"))
      read   = nested(scope.source.current)
      first  <- scope.withFields(Fields(Field("parent", 1)))(read)
      second <- scope.withFields(Fields(Field("parent", 2)))(read)
      failed <- scope
        .scope(LogContext(Mdc("id" -> "temporary"), Fields(Field("id", 3))))(
          IO.raiseError[Unit](failure)
        )
        .attempt
      restored <- scope.current
    } yield {
      assertEquals(first.mdc.values, Map("id" -> "nested"))
      assertEquals(second.mdc.values, Map("id" -> "nested"))
      assertEquals(
        first.fields.encode(org.typelevel.log4cats.experimental.encoding.ValueEncoding.value),
        Value.Obj(Map("parent" -> Value.Int64(1)))
      )
      assertEquals(
        second.fields.encode(org.typelevel.log4cats.experimental.encoding.ValueEncoding.value),
        Value.Obj(Map("parent" -> Value.Int64(2)))
      )
      assertEquals(failed, Left(failure): Either[Throwable, Unit])
      assert(restored.mdc.isEmpty)
      assert(restored.fields.isEmpty)
    }
  }

  test("IO replacement clears both channels and restores the parent on cancellation and failure") {
    import cats.effect.Deferred
    import org.typelevel.log4cats.experimental.context.syntax._
    val parent  = LogContext(Mdc("id" -> "parent"), Fields(Field("id", 1)))
    val failure = new RuntimeException("replacement failed")
    for {
      capability <- ScopedContext.create.widen[LogScope[IO]]
      ready      <- Deferred[IO, LogContext]
      restored   <- Deferred[IO, LogContext]
      _ <- capability.scope(parent) {
        implicit val scope: LogScope[IO] = capability
        for {
          fiber <- (scope.current.flatMap(ready.complete) *> IO.never[Unit])
            .replaceContext(LogContext.empty)
            .onCancel(scope.current.flatMap(restored.complete).void)
            .start
          inside      <- ready.get
          sibling     <- scope.current
          _           <- fiber.cancel
          afterCancel <- restored.get
          failed <- IO
            .raiseError[Unit](failure)
            .replaceContext(LogContext(Mdc("id" -> "replacement"), Fields(Field("new", true))))
            .attempt
          afterFailure <- scope.current
        } yield {
          assert(inside.mdc.isEmpty)
          assert(inside.fields.isEmpty)
          List(sibling, afterCancel, afterFailure).foreach { context =>
            assertEquals(context.mdc.values, parent.mdc.values)
            assertEquals(
              context.fields.encode(org.typelevel.log4cats.experimental.encoding.ValueEncoding.value),
              parent.fields.encode(org.typelevel.log4cats.experimental.encoding.ValueEncoding.value)
            )
          }
          assertEquals(failed, Left(failure): Either[Throwable, Unit])
        }
      }
      after <- capability.current
    } yield {
      assert(after.mdc.isEmpty)
      assert(after.fields.isEmpty)
    }
  }

}
