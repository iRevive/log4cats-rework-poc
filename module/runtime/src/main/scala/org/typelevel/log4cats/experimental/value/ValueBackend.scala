package org.typelevel.log4cats.experimental.value

import cats.Applicative
import org.typelevel.log4cats.experimental.backend.{Backend, BoundBackend, EncodedEvent, EventMeta}
import org.typelevel.log4cats.experimental.encoding.ValueEncoding

object ValueBackend {
  def apply[F[_]](
      writer: EncodedEvent[Value] => F[Unit],
      capture0: F[Unit],
      accepts0: (EventMeta, Unit) => F[Boolean]
  )(implicit F: Applicative[F]): Backend[F] = Backend.constant(new BoundBackend[F] {
    type Context = Unit
    type Repr    = Value
    val encoding: ValueEncoding[Value]                           = ValueEncoding.value
    def capture(meta: EventMeta): F[Unit]                        = capture0
    def accepts(meta: EventMeta, context: Unit): F[Boolean]      = accepts0(meta, context)
    def emit(event: EncodedEvent[Value], context: Unit): F[Unit] = writer(event)
  })
}
