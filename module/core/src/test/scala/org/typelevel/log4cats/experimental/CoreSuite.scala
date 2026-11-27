package org.typelevel.log4cats.experimental

import munit.CatsEffectSuite
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

}
