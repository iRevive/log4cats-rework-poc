package org.typelevel.log4cats.experimental.context

import cats.effect.{IO, Ref}
import cats.syntax.all._
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.{Field, Fields}
import org.typelevel.log4cats.experimental.encoding.ValueEncoding
import org.typelevel.log4cats.experimental.value.Value

final class ContextSourceSuite extends CatsEffectSuite {
  test("sources compose in order, remain lazy, and read current values on every execution") {
    for {
      reads <- Ref.of[IO, Vector[String]](Vector.empty)
      value <- Ref.of[IO, Int](1)
      left = ContextSource(
        reads.update(_ :+ "left") *> value.get.map(n => Fields(Field("value", n), Field("winner", "left")))
      )
      right  = ContextSource(reads.update(_ :+ "right").as(Fields(Field("winner", "right"))))
      source = (left ++ right).map(_ ++ Fields(Field("mapped", true)))
      effect = source.current
      before <- reads.get
      first  <- effect
      _      <- value.set(2)
      second <- effect
      after  <- reads.get
    } yield {
      assertEquals(before, Vector.empty[String])
      assertEquals(after, Vector("left", "right", "left", "right"))
      def expected(n: Long): Value =
        Value.Obj(Map("value" -> Value.Int64(n), "winner" -> Value.Text("right"), "mapped" -> Value.Bool(true)))
      assertEquals(first.encode(ValueEncoding.value), expected(1))
      assertEquals(second.encode(ValueEncoding.value), expected(2))
    }
  }

  test("a failed source prevents later source reads") {
    val failure = new RuntimeException("source failure")
    for {
      reads <- Ref.of[IO, Int](0)
      left  = ContextSource(IO.raiseError[Fields](failure))
      right = ContextSource(reads.update(_ + 1).as(Fields.empty))
      result <- (left ++ right).current.attempt
      count  <- reads.get
    } yield {
      assert(result.swap.exists(_ eq failure))
      assertEquals(count, 0)
    }
  }
}
