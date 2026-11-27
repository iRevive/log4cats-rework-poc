package org.typelevel.log4cats.experimental.internal

import munit.FunSuite
import org.typelevel.log4cats.experimental.{Event, Mdc, RecordWriter}
import org.typelevel.log4cats.experimental.encoding.ValueEncoding
import org.typelevel.log4cats.experimental.value.Value

final class MdcSuite extends FunSuite {
  test("per-call MDC merges independently, and sealed writers reject MDC writes") {
    var escaped: RecordWriter = null
    val record = AccumulatingRecordWriter.capture { writer =>
      escaped = writer
      writer.mdc("id", "first")
      Event
        .message("event")
        .withMdc(Mdc("id" -> "second", "other" -> "value"))
        .withField("id", 42)
        .writeTo(writer)
      writer.mdc("id", "last")
    }
    assertEquals(record.mdc.values, Map("id" -> "last", "other" -> "value"))
    assertEquals(record.fields.encode(ValueEncoding.value), Value.Obj(Map("id" -> Value.Int64(42))))
    intercept[IllegalStateException](escaped.mdc("id", "escaped"))
    intercept[IllegalStateException](escaped.mdc(Mdc.empty))
  }

  test("MDC validates string entries and is right biased without altering structured values") {
    intercept[IllegalArgumentException](Mdc("" -> "value"))
    intercept[IllegalArgumentException](Mdc((null: String) -> "value"))
    intercept[IllegalArgumentException](Mdc("key" -> (null: String)))
    val left = Mdc("key" -> "left")
    assertEquals((left ++ Mdc("key" -> "right")).values, Map("key" -> "right"))
    assertEquals(left.values, Map("key" -> "left"))
  }
}
