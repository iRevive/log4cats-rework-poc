package org.typelevel.log4cats.experimental.otel

import org.typelevel.log4cats.experimental.encoding.{ArrayBuilder, ObjectBuilder, ValueEncoding}
import org.typelevel.otel4s.AnyValue

object AnyValueEncoding {
  val anyValue: ValueEncoding[AnyValue] = new ValueEncoding[AnyValue] {
    def empty: AnyValue = AnyValue.empty
    def text(value: String): AnyValue = {
      require(value != null, "text cannot be null"); AnyValue.string(value)
    }
    def boolean(value: Boolean): AnyValue = AnyValue.boolean(value)
    def int64(value: Long): AnyValue      = AnyValue.long(value)
    def float64(value: Double): AnyValue  = AnyValue.double(value)
    def bytes(value: Array[Byte]): AnyValue = {
      require(value != null, "bytes cannot be null"); AnyValue.bytes(value)
    }
    def obj(): ObjectBuilder[AnyValue] = new ObjectBuilder[AnyValue] {
      private var open   = true
      private val values = scala.collection.mutable.Map.empty[String, AnyValue]
      def put(name: String, value: AnyValue): Unit = {
        check(); require(name != null, "object key cannot be null");
        values.update(name, value)
      }
      def result(): AnyValue = { check(); open = false; AnyValue.map(values.toMap) }
      private def check(): Unit =
        if (!open) throw new IllegalStateException("object builder is sealed")
    }
    def array(): ArrayBuilder[AnyValue] = new ArrayBuilder[AnyValue] {
      private var open               = true
      private val values             = Vector.newBuilder[AnyValue]
      def add(value: AnyValue): Unit = { check(); values += value }
      def result(): AnyValue         = { check(); open = false; AnyValue.seq(values.result()) }
      private def check(): Unit =
        if (!open) throw new IllegalStateException("array builder is sealed")
    }
  }
}
