package org.typelevel.log4cats.experimental.slf4j

import java.util.{ArrayList, Base64, Collections, LinkedHashMap}
import org.typelevel.log4cats.experimental.encoding.{ArrayBuilder, ObjectBuilder, ValueEncoding}

object Slf4jEncoding {
  val native: ValueEncoding[AnyRef] = new ValueEncoding[AnyRef] {
    def empty: AnyRef = null
    def text(value: String): AnyRef = {
      require(value != null, "text cannot be null")
      value
    }
    def boolean(value: Boolean): AnyRef = java.lang.Boolean.valueOf(value)
    def int64(value: Long): AnyRef      = java.lang.Long.valueOf(value)
    def float64(value: Double): AnyRef  = java.lang.Double.valueOf(value)
    def bytes(value: Array[Byte]): AnyRef = {
      require(value != null, "bytes cannot be null")
      Collections.singletonMap[String, String]("$bytes", Base64.getEncoder.encodeToString(value))
    }
    def obj(): ObjectBuilder[AnyRef] = new ObjectBuilder[AnyRef] {
      private var open   = true
      private val values = new LinkedHashMap[String, AnyRef]()

      def put(name: String, value: AnyRef): Unit = {
        checkOpen()
        require(name != null, "object key cannot be null")
        values.put(name, value)
        ()
      }

      def result(): AnyRef = {
        checkOpen()
        open = false
        Collections.unmodifiableMap[String, AnyRef](new LinkedHashMap[String, AnyRef](values))
      }

      private def checkOpen(): Unit =
        if (!open) throw new IllegalStateException("object builder is sealed")
    }
    def array(): ArrayBuilder[AnyRef] = new ArrayBuilder[AnyRef] {
      private var open   = true
      private val values = new ArrayList[AnyRef]()

      def add(value: AnyRef): Unit = {
        checkOpen()
        values.add(value)
        ()
      }

      def result(): AnyRef = {
        checkOpen()
        open = false
        Collections.unmodifiableList[AnyRef](new ArrayList[AnyRef](values))
      }

      private def checkOpen(): Unit =
        if (!open) throw new IllegalStateException("array builder is sealed")
    }
  }
}
