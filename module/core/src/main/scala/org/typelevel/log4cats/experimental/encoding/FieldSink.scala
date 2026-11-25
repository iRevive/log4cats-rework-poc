package org.typelevel.log4cats.experimental.encoding

trait FieldSink[V] {
  def put(name: String, value: V): Unit
}
