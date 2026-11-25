package org.typelevel.log4cats.experimental.encoding

import org.typelevel.log4cats.experimental.value.Value

trait ValueEncoding[V] {
  def empty: V
  def text(value: String): V
  def boolean(value: Boolean): V
  def int64(value: Long): V
  def float64(value: Double): V
  def bytes(value: Array[Byte]): V
  def obj(): ObjectBuilder[V]
  def array(): ArrayBuilder[V]
}

object ValueEncoding {
  val value: ValueEncoding[Value] = new ValueEncoding[Value] {
    def empty: Value               = Value.Empty
    def text(v: String): Value     = { require(v != null, "text cannot be null"); Value.Text(v) }
    def boolean(v: Boolean): Value = Value.Bool(v)
    def int64(v: Long): Value      = Value.Int64(v)
    def float64(v: Double): Value  = Value.Float64(v)
    def bytes(v: Array[Byte]): Value = {
      require(v != null, "bytes cannot be null"); Value.Bytes(v.toVector)
    }
    def obj(): ObjectBuilder[Value] = new ObjectBuilder[Value] {
      private var open   = true
      private val fields = scala.collection.mutable.Map.empty[String, Value]
      def put(name: String, v: Value): Unit = {
        check(); require(name != null, "object key cannot be null");
        fields.update(name, v)
      }
      def result(): Value = { check(); open = false; Value.Obj(fields.toMap) }
      private def check(): Unit =
        if (!open) throw new IllegalStateException("object builder is sealed")
    }
    def array(): ArrayBuilder[Value] = new ArrayBuilder[Value] {
      private var open        = true
      private val values      = Vector.newBuilder[Value]
      def add(v: Value): Unit = { check(); values += v }
      def result(): Value     = { check(); open = false; Value.Arr(values.result()) }
      private def check(): Unit =
        if (!open) throw new IllegalStateException("array builder is sealed")
    }
  }
}
