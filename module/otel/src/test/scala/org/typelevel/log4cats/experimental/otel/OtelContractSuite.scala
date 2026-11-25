package org.typelevel.log4cats.experimental.otel

import cats.effect.{IO, Resource}
import cats.syntax.all._
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.encoding.{Encoder, ValueEncoding}
import org.typelevel.log4cats.experimental.pipeline.Pipeline
import org.typelevel.log4cats.experimental.{Field, Fields, Level, Scope}
import org.typelevel.otel4s.AnyValue
import org.typelevel.otel4s.oteljava.AnyValueConverters._
import org.typelevel.otel4s.oteljava.testkit.logs.LogsTestkit

final class OtelContractSuite extends CatsEffectSuite {
  test(
    "SDK export preserves portable values in both bodies and structured attributes"
  ) {
    val bytes = Array[Byte](1, 2, 3)
    val encoder = new Encoder[Unit] {
      def encode[V](value: Unit)(out: ValueEncoding[V]): V = {
        val fields = out.obj()
        fields.put("bytes", out.bytes(bytes))
        fields.put("empty", out.empty)
        fields.put("text", out.text(""))
        fields.put("zero", out.int64(0L))
        fields.put("min", out.int64(Long.MinValue))
        fields.put("max", out.int64(Long.MaxValue))
        fields.put("nan", out.float64(Double.NaN))
        fields.put("positive", out.float64(Double.PositiveInfinity))
        fields.put("negative", out.float64(Double.NegativeInfinity))
        fields.put("array.empty", out.array().result())
        fields.put("object.empty", out.obj().result())
        val array = out.array()
        array.add(out.boolean(false))
        array.add(out.empty)
        val nested = out.obj()
        nested.put("", out.text("nested"))
        array.add(nested.result())
        fields.put("array.mixed", array.result())
        fields.result()
      }
    }
    val expected = Map[String, AnyValue](
      "bytes"        -> AnyValue.bytes(Array[Byte](1, 2, 3)),
      "empty"        -> AnyValue.empty,
      "text"         -> AnyValue.string(""),
      "zero"         -> AnyValue.long(0L),
      "min"          -> AnyValue.long(Long.MinValue),
      "max"          -> AnyValue.long(Long.MaxValue),
      "positive"     -> AnyValue.double(Double.PositiveInfinity),
      "negative"     -> AnyValue.double(Double.NegativeInfinity),
      "array.empty"  -> AnyValue.seq(Vector.empty),
      "object.empty" -> AnyValue.map(Map.empty),
      "array.mixed" -> AnyValue.seq(
        Vector(
          AnyValue.boolean(false),
          AnyValue.empty,
          AnyValue.map(Map("" -> AnyValue.string("nested")))
        )
      )
    )

    def check(value: AnyValue): Unit = value match {
      case obj: AnyValue.MapValue =>
        assertEquals(obj.value - "nan", expected)
        obj.value("nan") match {
          case number: AnyValue.DoubleValue => assert(number.value.isNaN)
          case other                        => fail("NaN lost its double representation: " + other)
        }
      case other => fail("Structured object became " + other)
    }

    LogsTestkit.inMemory[IO]().use { kit =>
      val plan = Pipeline.fromBackend[IO](OtelBackend.resource(kit.loggerProvider))
      Pipeline.runtimeResource(plan).use { runtime =>
        for {
          delivery <- runtime.prepare(Scope("all-values"), Level.Info, None) { writer =>
            writer.body(())(encoder)
            writer.fields(Fields(Field("structured", ())(encoder), Field("empty", Option.empty[String])))
          }
          _       <- IO { bytes(0) = 99 }
          _       <- delivery.deliver
          records <- kit.finishedLogs
        } yield {
          assertEquals(records.size, 1)
          val record = records.head
          check(record.getBodyValue.toScala)
          check(
            record.getAttributes
              .get(io.opentelemetry.api.common.AttributeKey.valueKey("structured"))
              .toScala
          )
          val empty =
            record.getAttributes.get(io.opentelemetry.api.common.AttributeKey.valueKey("empty"))
          assert(empty != null, "An explicit empty attribute must remain present")
          assertEquals(empty.toScala, AnyValue.empty)
        }
      }
    }
  }
}
