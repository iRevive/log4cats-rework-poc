package org.typelevel.log4cats.experimental

import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.encoding.{Encoder, ValueEncoding}
import org.typelevel.log4cats.experimental.value.Value

final class CoreContractSuite extends CatsEffectSuite {
  test("empty, omitted, nonfinite, and invalid field names remain distinct") {
    val out = ValueEncoding.value
    assertEquals(Encoder[Float].encode(0.5f)(out), Value.Float64(0.5): Value)
    assertEquals(
      Encoder[Double].encode(Double.PositiveInfinity)(out),
      Value.Float64(Double.PositiveInfinity): Value
    )
    assertEquals(
      Encoder[Double].encode(Double.NegativeInfinity)(out),
      Value.Float64(Double.NegativeInfinity): Value
    )
    assertEquals(
      Encoder[Array[Byte]].encode(Array.empty[Byte])(out),
      Value.Bytes(Vector.empty): Value
    )
    assertEquals(Encoder[String].encode("")(out), Value.Text(""): Value)
    assertEquals(Encoder[Boolean].encode(false)(out), Value.Bool(false): Value)
    assertEquals(Encoder[Long].encode(0L)(out), Value.Int64(0): Value)
    val omitted = Fields.omit(true, throw new AssertionError("omitted field constructed"))
    assertEquals(omitted.encode(out), Value.Obj(Map.empty): Value)
    assertEquals(
      Fields(Field("none", Option.empty[Int])).encode(out),
      Value.Obj(Map("none" -> Value.Empty)): Value
    )
    assertEquals(
      Encoder[Map[String, Int]].encode(Map("" -> 1))(out),
      Value.Obj(Map("" -> Value.Int64(1))): Value
    )
    intercept[IllegalArgumentException](Field(null, 1))
    intercept[IllegalArgumentException](FieldKey[Int](""))
    val array = out.array()
    array.result()
    intercept[IllegalStateException](array.result())
  }
}
