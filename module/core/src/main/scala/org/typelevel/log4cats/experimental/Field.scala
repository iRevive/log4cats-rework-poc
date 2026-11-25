package org.typelevel.log4cats.experimental

import org.typelevel.log4cats.experimental.encoding.{Encoder, ValueEncoding}

sealed abstract class Field {
  def name: String
  def encode[V](out: ValueEncoding[V]): V
}

object Field {
  def apply[A](fieldName: String, value: A)(implicit encoder: Encoder[A]): Field = {
    validateName(fieldName)
    new Field {
      val name: String                        = fieldName
      def encode[V](out: ValueEncoding[V]): V = encoder.encode(value)(out)
    }
  }
  private[experimental] def validateName(name: String): Unit =
    require(name != null && name.nonEmpty, "top-level field names must be nonempty and nonnull")
}
