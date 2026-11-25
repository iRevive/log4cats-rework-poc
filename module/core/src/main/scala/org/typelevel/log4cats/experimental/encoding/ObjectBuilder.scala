package org.typelevel.log4cats.experimental.encoding

trait ObjectBuilder[V] {
  def put(name: String, value: V): Unit
  def result(): V
}
