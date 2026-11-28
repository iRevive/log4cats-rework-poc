package consumer

import cats.data.Kleisli
import cats.effect.IO
import cats.syntax.all._
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.context.{LogContext, LogScope}
import org.typelevel.log4cats.experimental.context.syntax._
import org.typelevel.log4cats.experimental.{Field, Fields, Mdc}
import org.typelevel.log4cats.experimental.encoding.ValueEncoding
import org.typelevel.log4cats.experimental.value.Value

final class LogScopeSyntaxSuite extends CatsEffectSuite {
  private type Reader[A] = Kleisli[IO, LogContext, A]

  test("effect syntax uses the implicit scope; inner MDC wins and fields remain additive") {
    implicit val logScope: LogScope[Reader] = LogScope.fromLocal[Reader]
    val scope                               = LogScope[Reader]
    val read = scope.current
      .withMdc(Mdc("id" -> "inner"))
      .withMdc(Mdc("id" -> "outer", "added" -> "kept"))
      .withFields(Fields(Field("id", 2)))
      .scope(LogContext(fields = Fields(Field("scope", true))))
    val original = LogContext(Mdc("id" -> "original", "ambient" -> "kept"), Fields(Field("id", 1)))
    (for {
      value <- read
      after <- scope.current
    } yield {
      assertEquals(value.mdc.values, Map("id" -> "inner", "ambient" -> "kept", "added" -> "kept"))
      assertEquals(
        value.fields.encode(ValueEncoding.value),
        Value.Obj(Map("id" -> Value.Int64(2), "scope" -> Value.Bool(true)))
      )
      assertEquals(after.mdc.values, original.mdc.values)
      assertEquals(after.fields.encode(ValueEncoding.value), original.fields.encode(ValueEncoding.value))
    }).run(original)
  }

  test("replacement removes both inherited channels, permits inner additions, and restores on success or failure") {
    implicit val scope: LogScope[Reader] = LogScope.fromLocal[Reader]
    val original                         = LogContext(Mdc("old" -> "mdc"), Fields(Field("old", true)))
    val replacement                      = LogContext(Mdc("new" -> "mdc"), Fields(Field("new", true)))
    val failure                          = new RuntimeException("replacement failed")
    val failed: Reader[Int]              = Kleisli.liftF(IO.raiseError[Int](failure))
    val scopedRead                       = scope.current.withMdc(Mdc("inner" -> "added")).replaceContext(replacement)
    val pureNumber: Reader[Int]          = Kleisli.pure(42)
    val program = for {
      value        <- scopedRead
      cleared      <- scope.current.replaceContext(LogContext.empty)
      number       <- pureNumber.replaceContext(replacement)
      failedResult <- failed.replaceContext(LogContext.empty).attempt
      after        <- scope.current
    } yield {
      assertEquals(value.mdc.values, Map("new" -> "mdc", "inner" -> "added"))
      assertEquals(value.fields.encode(ValueEncoding.value), Value.Obj(Map("new" -> Value.Bool(true))))
      assert(cleared.mdc.isEmpty)
      assert(cleared.fields.isEmpty)
      assertEquals(number, 42)
      assertEquals(failedResult, Left(failure): Either[Throwable, Int])
      assertEquals(after.mdc.values, original.mdc.values)
      assertEquals(after.fields.encode(ValueEncoding.value), original.fields.encode(ValueEncoding.value))
    }
    program.run(original)
  }
}
