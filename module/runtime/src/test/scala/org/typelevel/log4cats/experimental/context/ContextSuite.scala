package org.typelevel.log4cats.experimental.context

import cats.data.Kleisli
import cats.effect.{IO, Ref, Resource}
import cats.implicits._
import cats.mtl.{Ask, Local}
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.backend.{Backend, EncodedEvent}
import org.typelevel.log4cats.experimental.pipeline.Pipeline
import org.typelevel.log4cats.experimental.value.{Value, ValueBackend}
import org.typelevel.log4cats.experimental.{Event, Field, Fields}

final class ContextSuite extends CatsEffectSuite {
  private type Reader[A] = Kleisli[IO, Fields, A]

  private val readerLocal: Local[Reader, Fields] = implicitly[Local[Reader, Fields]]

  test("Ask supplies ambient fields and Local scopes right-biased nested fields") {
    val ask: Ask[Reader, Fields] = readerLocal
    val source                   = ContextSource.fromAsk[Reader](ask)

    val nested: Reader[(Fields, Fields, Fields)] = for {
      before <- source.current
      inner <- LoggingContext.withFields[Reader, Fields](Fields(Field("k", "inner"))) {
        source.current
      }
      after <- source.current
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
}
