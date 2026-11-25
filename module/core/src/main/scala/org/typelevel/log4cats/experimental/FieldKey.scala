package org.typelevel.log4cats.experimental

import org.typelevel.log4cats.experimental.encoding.Encoder

final class FieldKey[A](val name: String)(implicit val encoder: Encoder[A]) {
  Field.validateName(name)
  def apply(value: A): Field = Field(name, value)(encoder)
}

object FieldKey {
  def apply[A](name: String)(implicit encoder: Encoder[A]): FieldKey[A] = new FieldKey[A](name)
}
