package org.typelevel.log4cats.experimental.value

import cats.Applicative
import org.typelevel.log4cats.experimental.backend.{Backend, BoundBackend, EncodedEvent, EventMeta}
import org.typelevel.log4cats.experimental.encoding.ValueEncoding
import org.typelevel.vault.Vault

object ValueBackend {
  def apply[F[_]](
      writer: EncodedEvent[Value] => F[Unit],
      capture0: F[Vault],
      accepts0: (EventMeta, Vault) => F[Boolean]
  )(implicit F: Applicative[F]): Backend[F] = Backend.constant(new BoundBackend[F] {
    type Repr = Value
    val encoding: ValueEncoding[Value]                           = ValueEncoding.value
    def capture(meta: EventMeta): F[Vault]                       = capture0
    def accepts(meta: EventMeta, attachments: Vault): F[Boolean] = accepts0(meta, attachments)
    def emit(event: EncodedEvent[Value]): F[Unit]                = writer(event)
  })
}
