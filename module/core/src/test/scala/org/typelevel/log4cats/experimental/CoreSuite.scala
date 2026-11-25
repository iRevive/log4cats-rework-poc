package org.typelevel.log4cats.experimental

import cats.effect.{Deferred, IO}
import cats.syntax.all._
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.backend.{EncodedEvent, EventMeta}
import org.typelevel.log4cats.experimental.console.ConsoleRenderer
import org.typelevel.log4cats.experimental.context.ScopedFields
import org.typelevel.log4cats.experimental.encoding.{Encoder, ValueEncoding}
import org.typelevel.log4cats.experimental.value.Value

final class CoreSuite extends CatsEffectSuite {
  private case class Person(name: String, attempts: Long)
  private implicit val personEncoder: Encoder[Person] = new Encoder[Person] {
    def encode[V](p: Person)(out: ValueEncoding[V]): V = {
      val b = out.obj()
      b.put("name", out.text(p.name))
      b.put("attempts", out.int64(p.attempts))
      val a = out.array()
      a.add(out.text("x")); a.add(out.boolean(false)); a.add(out.empty)
      b.put("mixed", a.result())
      b.result()
    }
  }

  test("Value algebra preserves primitive, empty, bytes, and sealed snapshots") {
    val out     = ValueEncoding.value
    val bytes   = Array[Byte](1, 2, 3)
    val encoded = Encoder[Array[Byte]].encode(bytes)(out)
    bytes(0) = 99
    assertEquals(encoded, Value.Bytes(Vector[Byte](1, 2, 3)))
    assertEquals(Encoder[Long].encode(Long.MinValue)(out), Value.Int64(Long.MinValue))
    assert(Encoder[Double].encode(Double.NaN)(out).asInstanceOf[Value.Float64].value.isNaN)
    assertEquals(Encoder[Option[String]].encode(None)(out), Value.Empty)
    assertEquals(Encoder[List[Int]].encode(Nil)(out), Value.Arr(Vector.empty))
    assertEquals(Encoder[Map[String, Int]].encode(Map.empty)(out), Value.Obj(Map.empty))
    val ob       = out.obj(); ob.put("x", out.int64(1)); ob.put("x", out.int64(2))
    val snapshot = ob.result()
    intercept[IllegalStateException](ob.put("later", out.empty))
    intercept[IllegalStateException](ob.result())
    val ab = out.array(); ab.add(out.text("first")); ab.add(out.text("second"))
    assertEquals(ab.result(), Value.Arr(Vector(Value.Text("first"), Value.Text("second"))))
    intercept[IllegalStateException](ab.add(out.empty))
    assertEquals(snapshot, Value.Obj(Map("x" -> Value.Int64(2))))
  }

  test("one domain encoder builds nested heterogeneous values and instances compose") {
    val p     = Person("Ada", Long.MaxValue)
    val value = personEncoder.encode(p)(ValueEncoding.value)
    assertEquals(
      value,
      Value.Obj(
        Map(
          "name"     -> Value.Text("Ada"),
          "attempts" -> Value.Int64(Long.MaxValue),
          "mixed"    -> Value.Arr(Vector(Value.Text("x"), Value.Bool(false), Value.Empty))
        )
      )
    )
    val mapped = Encoder[Int].contramap[String](_.length).encode("abcd")(ValueEncoding.value)
    assertEquals(mapped, Value.Int64(4L))
    assertEquals(
      Encoder[Vector[Option[Int]]].encode(Vector(Some(1), None))(ValueEncoding.value),
      Value.Arr(Vector(Value.Int64(1), Value.Empty))
    )
    assertEquals(
      Encoder[Map[String, Int]].encode(Map("z" -> 1, "a" -> 2))(ValueEncoding.value),
      Value.Obj(Map("z" -> Value.Int64(1), "a" -> Value.Int64(2)))
    )
  }

  test("heterogeneous fields, FieldKey, right-biased winners, and validation") {
    var discarded = 0
    val counted = new Encoder[Int] {
      def encode[V](v: Int)(out: ValueEncoding[V]): V = { discarded += 1; out.int64(v.toLong) }
    }
    val left    = Fields(FieldKey[Int]("answer")(counted)(1), Field("flag", true))
    val right   = Fields(Field("answer", "winner"))
    val encoded = (left ++ right).encode(ValueEncoding.value)
    assertEquals(
      encoded,
      Value.Obj(Map("answer" -> Value.Text("winner"), "flag" -> Value.Bool(true)))
    )
    assertEquals(discarded, 0)
    assertEquals(Fields.omit(true, Field("x", 1)).isEmpty, true)
    intercept[IllegalArgumentException](Field("", 1))
  }

  test("nested IOLocal scopes restore the immediate parent after success and failure") {
    for {
      scoped <- ScopedFields.create
      _ <- scoped.scope(Fields(Field("parent", "outer"))) {
        for {
          _            <- scoped.scope(Fields(Field("parent", "inner")))(IO.unit)
          afterSuccess <- scoped.current
          _ <- scoped
            .scope(Fields(Field("parent", "inner")))(IO.raiseError[Unit](new RuntimeException("expected")))
            .attempt
          afterError <- scoped.current
        } yield {
          val expected: Value = Value.Obj(Map("parent" -> Value.Text("outer")))
          assertEquals(afterSuccess.encode(ValueEncoding.value), expected)
          assertEquals(afterError.encode(ValueEncoding.value), expected)
        }
      }
      after <- scoped.current
    } yield assert(after.isEmpty)
  }

  test("IOLocal child inheritance, sibling isolation, and cancellation restoration") {
    for {
      scoped          <- ScopedFields.create
      entered         <- Deferred[IO, Unit]
      childContext    <- Deferred[IO, Fields]
      siblingContext  <- Deferred[IO, Fields]
      outsideOnCancel <- Deferred[IO, Fields]
      canceled <- scoped
        .scope(Fields(Field("x", "outer"))) {
          for {
            inherited <- scoped.current
            left <- scoped
              .scope(Fields(Field("x", "left"))) {
                scoped.current
                  .flatMap(childContext.complete)
                  .void *> entered.complete(()).void *> IO.never[Fields]
              }
              .onCancel(scoped.current.flatMap(outsideOnCancel.complete).void)
              .start
            _             <- entered.get
            sibling       <- scoped.current.flatMap(siblingContext.complete).void.start
            _             <- sibling.joinWithNever
            child         <- childContext.get
            siblingFields <- siblingContext.get
            parent        <- scoped.current
            _             <- left.cancel
            duringCancel  <- outsideOnCancel.get
            _ <- IO(
              assertEquals(
                duringCancel.encode(ValueEncoding.value),
                Fields(Field("x", "outer")).encode(ValueEncoding.value)
              )
            )
          } yield (inherited, child, siblingFields, parent)
        }
        .attempt
      after <- scoped.current
    } yield {
      assert(canceled.isRight)
      assertEquals(
        canceled.toOption.get._1.encode(ValueEncoding.value),
        Fields(Field("x", "outer")).encode(ValueEncoding.value)
      )
      assertEquals(
        canceled.toOption.get._2.encode(ValueEncoding.value),
        Fields(Field("x", "left")).encode(ValueEncoding.value)
      )
      assertEquals(
        canceled.toOption.get._3.encode(ValueEncoding.value),
        Fields(Field("x", "outer")).encode(ValueEncoding.value)
      )
      assertEquals(
        canceled.toOption.get._4.encode(ValueEncoding.value),
        Fields(Field("x", "outer")).encode(ValueEncoding.value)
      )
      assert(after.isEmpty)
    }
  }

  test("renderer sorts keys, escapes controls, and includes stack frames") {
    val ex = new IllegalStateException("bad\nvalue")
    val event: EncodedEvent[Value] = EncodedEvent(
      EventMeta(Level.Error, Scope("z.scope"), Some("event"), 7L),
      Value.Obj(Map("quote\"" -> Value.Text("line\n"))),
      Value.Obj(Map("z" -> Value.Int64(2), "a" -> Value.Bool(true))),
      Some(ex),
      org.typelevel.vault.Vault.empty
    )
    val rendered = ConsoleRenderer.render(event)
    assert(rendered.indexOf("\"a\"") < rendered.indexOf("\"z\""))
    assert(rendered.contains("line\\n"))
    assert(rendered.contains("java.lang.IllegalStateException"))
    assert(rendered.contains("at org.typelevel.log4cats.experimental.CoreSuite"))
  }
}
