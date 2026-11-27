package org.typelevel.log4cats.experimental.circe

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._
import io.circe.Json
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.backend.{Backend, BoundBackend, EncodedEvent, EventMeta}
import org.typelevel.log4cats.experimental.context.ContextSource
import org.typelevel.log4cats.experimental.encoding.ValueEncoding
import org.typelevel.log4cats.experimental.pipeline.Pipeline
import org.typelevel.log4cats.experimental.value.Value
import org.typelevel.log4cats.experimental.{Event, Field, Fields, Level, Scope}

class CirceSuite extends CatsEffectSuite {
  private val out = JsonEncoding.json

  test("all primitive policies preserve long precision and snapshot bytes") {
    val bytes   = Array[Byte](1, 2, 3)
    val encoded = out.bytes(bytes)
    bytes(0) = 9
    assertEquals(out.empty, Json.Null)
    assertEquals(out.text("hello"), Json.fromString("hello"))
    assertEquals(out.boolean(true), Json.True)
    assertEquals(out.int64(Long.MaxValue).asNumber.flatMap(_.toLong), Some(Long.MaxValue))
    assertEquals(out.float64(1.5), Json.fromDoubleOrNull(1.5))
    assertEquals(out.float64(Double.NaN), Json.fromString("NaN"))
    assertEquals(out.float64(Double.PositiveInfinity), Json.fromString("Infinity"))
    assertEquals(out.float64(Double.NegativeInfinity), Json.fromString("-Infinity"))
    assertEquals(encoded, Json.obj("$bytes" -> Json.fromString("AQID")))
    intercept[IllegalArgumentException](out.text(null))
    intercept[IllegalArgumentException](out.bytes(null))
  }

  test("object and array builders seal and duplicate keys use the last value") {
    val obj = out.obj()
    obj.put("key", out.int64(1))
    obj.put("key", out.int64(2))
    assertEquals(obj.result(), Json.obj("key" -> Json.fromInt(2)))
    intercept[IllegalStateException](obj.put("late", Json.Null))
    intercept[IllegalStateException](obj.result())
    val array = out.array()
    array.add(Json.Null)
    array.add(Json.True)
    assertEquals(array.result(), Json.arr(Json.Null, Json.True))
    intercept[IllegalStateException](array.add(Json.Null))
    intercept[IllegalStateException](array.result())
    intercept[IllegalArgumentException](out.obj().put(null, Json.Null))
  }

  test("existing domain encoders produce nested JSON without an intermediate Value") {
    val fields = Fields(
      Field("nested", Map("items" -> List(1, 2))),
      Field("absent", Option.empty[String])
    )
    assertEquals(
      fields.encode(out),
      Json.obj(
        "nested" -> Json.obj("items" -> Json.arr(Json.fromInt(1), Json.fromInt(2))),
        "absent" -> Json.Null
      )
    )
  }

  test("JSON and Value fan-out share capture, preserve precedence, and snapshot queued replay") {
    for {
      jsonRecords  <- Ref.of[IO, Vector[EncodedEvent[Json]]](Vector.empty)
      valueRecords <- Ref.of[IO, Vector[EncodedEvent[Value]]](Vector.empty)
      captures     <- Ref.of[IO, Int](0)
      source = ContextSource(
        captures
          .update(_ + 1)
          .as(
            Fields(Field("request", "req-1"), Field("winner", "ambient"))
          )
      )
      json = Pipeline
        .fromBackend[IO](
          Resource.pure[IO, Backend[IO]](
            CirceBackend[IO](event => jsonRecords.update(_ :+ event))
          )
        )
        .async(2)
      value = Pipeline.fromBackend[IO](Resource.pure[IO, Backend[IO]](Backend.constant(new BoundBackend[IO] {
        type Context = Unit
        type Repr    = Value
        val encoding                                                  = ValueEncoding.value
        def capture(meta: EventMeta): IO[Unit]                        = IO.pure(())
        def accepts(meta: EventMeta, context: Unit): IO[Boolean]      = IO.pure(true)
        def emit(event: EncodedEvent[Value], context: Unit): IO[Unit] = valueRecords.update(_ :+ event)
      })))
      plan = (json ++ value).withFields(Fields(Field("winner", "static")))
      _ <- Pipeline.runtimeResource(plan, source).use { runtime =>
        val logger = runtime.fromNameUnsafe("json-test")
        val bytes  = Array[Byte](1, 2, 3)
        for {
          prepared <- runtime.prepare(Scope("json-test"), Level.Info, Some("json.event")) { writer =>
            writer.body("body")
            writer.field("winner", "call")
            writer.field("bytes", bytes)
          }
          _ <- IO { bytes(0) = 9 }
          _ <- prepared.deliver *> prepared.deliver *> runtime.flush
          _ <- logger.debugEvent(Event.empty)
          _ <- runtime.flush
        } yield ()
      }
      js    <- jsonRecords.get
      vs    <- valueRecords.get
      count <- captures.get
    } yield {
      assertEquals(js.size, 3)
      assertEquals(vs.size, 3)
      assertEquals(count, 2)
      assertEquals(js.head.meta, vs.head.meta)
      assertEquals(js.head, js(1))
      assertEquals(
        js.head.fields,
        Json.obj(
          "request" -> Json.fromString("req-1"),
          "winner"  -> Json.fromString("call"),
          "bytes"   -> Json.obj("$bytes" -> Json.fromString("AQID"))
        )
      )
      assertEquals(js.head.body, Json.fromString("body"))
      assertEquals(js.last.body, Json.Null)
    }
  }

  test("coarse rejection skips event construction and JSON emission") {
    var built   = 0
    val backend = CirceBackend[IO](_ => IO.raiseError(new AssertionError("unexpected emit")))
    val plan    = Pipeline.fromBackend[IO](Resource.pure[IO, Backend[IO]](backend)).atLeast(Level.Error)
    Pipeline
      .runtimeResource(plan)
      .use { runtime =>
        val logger = runtime.fromNameUnsafe("disabled")
        logger.infoEvent { built += 1; Event.body("unused") }
      }
      .map(_ => assertEquals(built, 0))
  }
}
