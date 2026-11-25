package org.typelevel.log4cats.experimental.backend

import org.typelevel.log4cats.experimental.encoding.{Encoder, ValueEncoding}

sealed abstract class Body {
  def encode[V](out: ValueEncoding[V]): V
}

object Body {
  def apply[A](value: A)(implicit encoder: Encoder[A]): Body = new Body {
    def encode[V](out: ValueEncoding[V]): V = encoder.encode(value)(out)
  }
  val empty: Body = new Body { def encode[V](out: ValueEncoding[V]): V = out.empty }
}
