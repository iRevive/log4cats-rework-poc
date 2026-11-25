package org.typelevel.log4cats.experimental

import org.typelevel.log4cats.experimental.backend.Body
import org.typelevel.log4cats.experimental.encoding.Encoder

trait RecordWriter {

  def body[A](value: A)(implicit encoder: Encoder[A]): Unit
  def body(value: Body): Unit
  def field[A](name: String, value: A)(implicit encoder: Encoder[A]): Unit
  def fields(value: Fields): Unit

  def cause(error: Throwable): Unit
}
