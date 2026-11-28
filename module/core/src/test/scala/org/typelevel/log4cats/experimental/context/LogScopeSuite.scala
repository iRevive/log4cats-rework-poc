package org.typelevel.log4cats.experimental.context

import cats.~>
import cats.data.Kleisli
import cats.effect.IO
import cats.syntax.all._
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.{Field, Fields, Mdc}
import org.typelevel.log4cats.experimental.encoding.ValueEncoding
import org.typelevel.log4cats.experimental.value.Value

final class LogScopeSuite extends CatsEffectSuite {
  private type Reader[A] = Kleisli[IO, LogContext, A]

  test("Local-backed scopes share a source, nest by channel, and preserve result types") {
    val scope  = LogScope.fromLocal[Reader]
    val source = scope.source
    val outer: Reader ~> Reader = scope.scope(
      LogContext(Mdc("id" -> "outer"), Fields(Field("id", 2), Field("outer", true)))
    )
    val inner = scope.withMdc(Mdc("id" -> "inner"))
    val read  = source.current
    val program = for {
      before <- scope.current
      values <- outer {
        for {
          first  <- read
          nested <- inner(read)
          after  <- read
          result <- scope.withFields(Fields(Field("extra", true)))(Kleisli.pure[IO, LogContext, Int](42))
        } yield (first, nested, after, result)
      }
      after <- read
    } yield {
      assertEquals(before.mdc.values, Map("id" -> "ambient"))
      assertEquals(after.mdc.values, before.mdc.values)
      assertEquals(after.fields.encode(ValueEncoding.value), before.fields.encode(ValueEncoding.value))
      val (first, nested, restored, result) = values
      assertEquals(first.mdc.values, Map("id" -> "outer"))
      assertEquals(nested.mdc.values, Map("id" -> "inner"))
      assertEquals(restored.mdc.values, first.mdc.values)
      val expected = Value.Obj(Map("id" -> Value.Int64(2), "outer" -> Value.Bool(true)))
      assertEquals(first.fields.encode(ValueEncoding.value), expected)
      assertEquals(nested.fields.encode(ValueEncoding.value), expected)
      assertEquals(restored.fields.encode(ValueEncoding.value), expected)
      assertEquals(result, 42)
    }
    program.run(LogContext(Mdc("id" -> "ambient"), Fields(Field("id", 1))))
  }

  test("Local-backed scopes restore on failure and reused transformations read the current environment") {
    val scope     = LogScope.fromLocal[Reader]
    val failure   = new RuntimeException("scope failure")
    val addFields = scope.withFields(Fields(Field("static", true)))
    val failed = scope.withMdc(Mdc("id" -> "temporary"))(
      Kleisli.liftF[IO, LogContext, Unit](IO.raiseError(failure))
    )
    val read = addFields(scope.source.current)
    for {
      first  <- read.run(LogContext(mdc = Mdc("id" -> "first")))
      second <- read.run(LogContext(mdc = Mdc("id" -> "second")))
      result <- (for {
        failureResult <- failed.attempt
        restored      <- scope.current
      } yield (failureResult, restored)).run(LogContext(mdc = Mdc("id" -> "original")))
    } yield {
      assertEquals(first.mdc.values, Map("id" -> "first"))
      assertEquals(second.mdc.values, Map("id" -> "second"))
      assertEquals(first.fields.encode(ValueEncoding.value), Value.Obj(Map("static" -> Value.Bool(true))))
      assertEquals(second.fields.encode(ValueEncoding.value), first.fields.encode(ValueEncoding.value))
      assertEquals(result._1, Left(failure): Either[Throwable, Unit])
      assertEquals(result._2.mdc.values, Map("id" -> "original"))
      assert(result._2.fields.isEmpty)
    }
  }
}
