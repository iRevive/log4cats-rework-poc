package org.typelevel.log4cats.experimental.value

import scala.collection.immutable

sealed trait Value

object Value {
  case object Empty                                         extends Value
  final case class Text(value: String)                      extends Value
  final case class Bool(value: Boolean)                     extends Value
  final case class Int64(value: Long)                       extends Value
  final case class Float64(value: Double)                   extends Value
  final case class Bytes(value: Vector[Byte])               extends Value
  final case class Arr(value: Vector[Value])                extends Value
  final case class Obj(value: immutable.Map[String, Value]) extends Value
}
