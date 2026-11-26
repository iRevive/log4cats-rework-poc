package org.typelevel.log4cats.experimental.internal

import cats.effect.IO
import cats.~>
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.encoding.{Encoder, ValueEncoding}
import org.typelevel.log4cats.experimental.value.Value
import org.typelevel.log4cats.experimental.{Event, Level, Logger, RecordWriter}

final class FrontendSuite extends CatsEffectSuite {
  private type OptionF[A] = Option[A]
  test("Logger is a one-method SAM and a skipped callback stays lazy") {
    var invoked            = 0
    val logger: Logger[IO] = (_: Level, _: Option[String], _: RecordWriter => Unit) => IO.unit
    val description        = logger.infoEvent { invoked += 1; Event.message("lazy") }
    assertEquals(invoked, 0)
    description *> IO(assertEquals(invoked, 0))
  }

  test("event descriptions are immutable and reusable; rightmost fields encode only the winner") {
    var losingEncodes = 0
    val losing = new Encoder[Int] {
      def encode[V](value: Int)(out: ValueEncoding[V]): V = {
        losingEncodes += 1
        out.int64(value.toLong)
      }
    }
    val base  = Event.body("base").withField("answer", 1)(losing)
    val left  = base.withField("branch", true).withField("answer", "left")
    val right = base.withField("branch", false).withField("answer", "right")
    val a     = AccumulatingRecordWriter.capture(_.body("prelude"))
    val pa    = AccumulatingRecordWriter.capture(left.writeTo)
    val pb    = AccumulatingRecordWriter.capture(right.writeTo)
    assertEquals(a.body.encode(ValueEncoding.value), Value.Text("prelude"))
    assertEquals(pa.body.encode(ValueEncoding.value), Value.Text("base"))
    assertEquals(
      pa.fields.encode(ValueEncoding.value),
      Value.Obj(Map("answer" -> Value.Text("left"), "branch" -> Value.Bool(true)))
    )
    assertEquals(
      pb.fields.encode(ValueEncoding.value),
      Value.Obj(Map("answer" -> Value.Text("right"), "branch" -> Value.Bool(false)))
    )
    assertEquals(losingEncodes, 0)
  }

  test("mapK delegates through the wrapped SAM and maps each call") {
    var seen = Vector.empty[(Level, Option[String])]
    val base: Logger[OptionF] = new Logger[OptionF] {
      def log(
          level: Level,
          eventName: Option[String],
          write: RecordWriter => Unit
      ): Option[Unit] = {
        seen :+= ((level, eventName))
        Some(IO.unit)
      }
    }
    var mapped = 0
    val fk = new (OptionF ~> IO) {
      def apply[A](fa: Option[A]): IO[A] = {
        mapped += 1
        IO.fromOption(fa)(new NoSuchElementException("missing effect"))
      }
    }
    val adapted = base.mapK(fk)
    for {
      _ <- adapted.logEvent(Level.Warn, Some("warn.event"))(Event.message("x"))
      _ <- adapted.info("hello")
    } yield {
      assertEquals(mapped, 2)
      assertEquals(seen, Vector((Level.Warn, Some("warn.event")), (Level.Info, None)))
    }
  }

  test("writer cardinality is last body/cause wins and duplicate fields use rightmost value") {
    val firstCause = new RuntimeException("first")
    val lastCause  = new RuntimeException("last")
    val payload = AccumulatingRecordWriter.capture { writer =>
      writer.body("first")
      writer.body("last")
      writer.field("key", 1)
      writer.field("key", "winner")
      writer.cause(firstCause)
      writer.cause(lastCause)
    }
    assertEquals(payload.body.encode(ValueEncoding.value), Value.Text("last"))
    assertEquals(
      payload.fields.encode(ValueEncoding.value),
      Value.Obj(Map("key" -> Value.Text("winner")))
    )
    assertEquals(payload.cause, Some(lastCause))
  }

  test("writer is sealed after callback success and failure") {
    var escapedOnSuccess: RecordWriter = null
    AccumulatingRecordWriter.capture { writer =>
      escapedOnSuccess = writer
      writer.body("ok")
    }
    intercept[IllegalStateException](escapedOnSuccess.field("late", true))

    var escapedOnFailure: RecordWriter = null
    intercept[RuntimeException] {
      AccumulatingRecordWriter.capture { writer =>
        escapedOnFailure = writer
        throw new RuntimeException("callback failed")
      }
    }
    intercept[IllegalStateException](escapedOnFailure.cause(new RuntimeException("late")))
  }
}
