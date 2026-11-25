package org.typelevel.log4cats.experimental.circe

import io.circe.Json
import org.typelevel.log4cats.experimental.encoding.{ArrayBuilder, ObjectBuilder, ValueEncoding}

object JsonEncoding {
  val json: ValueEncoding[Json] = new ValueEncoding[Json] {
    def empty: Json = Json.Null
    def text(value: String): Json = {
      require(value != null, "text cannot be null")
      Json.fromString(value)
    }
    def boolean(value: Boolean): Json = Json.fromBoolean(value)
    def int64(value: Long): Json      = Json.fromLong(value)
    def float64(value: Double): Json  = Json.fromDoubleOrString(value)
    def bytes(value: Array[Byte]): Json = {
      require(value != null, "bytes cannot be null")
      Json.obj("$bytes" -> Json.fromString(java.util.Base64.getEncoder.encodeToString(value)))
    }
    def obj(): ObjectBuilder[Json] = new ObjectBuilder[Json] {
      private var open   = true
      private val values = scala.collection.mutable.LinkedHashMap.empty[String, Json]
      private def check(): Unit =
        if (!open) throw new IllegalStateException("object builder is sealed")
      def put(name: String, value: Json): Unit = {
        check()
        require(name != null, "object key cannot be null")
        values.update(name, value)
      }
      def result(): Json = {
        check()
        open = false
        Json.fromFields(values.toVector)
      }
    }
    def array(): ArrayBuilder[Json] = new ArrayBuilder[Json] {
      private var open   = true
      private val values = Vector.newBuilder[Json]
      private def check(): Unit =
        if (!open) throw new IllegalStateException("array builder is sealed")
      def add(value: Json): Unit = { check(); values += value }
      def result(): Json         = { check(); open = false; Json.fromValues(values.result()) }
    }
  }
}
