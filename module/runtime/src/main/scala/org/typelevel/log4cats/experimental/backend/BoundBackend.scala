package org.typelevel.log4cats.experimental.backend

import cats.effect.Sync
import cats.syntax.all._
import org.typelevel.log4cats.experimental.encoding.ValueEncoding
import org.typelevel.vault.Vault

trait BoundBackend[F[_]] {
  type Repr
  def encoding: ValueEncoding[Repr]
  def capture(meta: EventMeta): F[Vault]
  def accepts(meta: EventMeta, attachments: Vault): F[Boolean]
  def emit(event: EncodedEvent[Repr]): F[Unit]
  final private[experimental] def prepare(
      event: CapturedEvent
  )(implicit F: Sync[F]): F[Delivery[F]] = F.defer(prepareOutput(event))

  protected def prepareOutput(
      event: CapturedEvent
  )(implicit F: Sync[F]): F[Delivery[F]] =
    F.delay {
      EncodedEvent(
        event.meta,
        event.body.encode(encoding),
        event.fields.encode(encoding),
        event.cause,
        event.attachments
      )
    }.map(encoded => Delivery(F.defer(emit(encoded))))
}
