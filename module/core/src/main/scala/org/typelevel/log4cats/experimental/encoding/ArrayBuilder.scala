package org.typelevel.log4cats.experimental.encoding

trait ArrayBuilder[V] {
  def add(value: V): Unit
  def result(): V
}
