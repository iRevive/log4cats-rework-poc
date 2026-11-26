package org.typelevel.log4cats.experimental.backend

import cats.effect.Sync
import cats.syntax.all._
import org.typelevel.log4cats.experimental.Fields
import org.typelevel.log4cats.experimental.encoding.ValueEncoding

trait BoundBackend[F[_]] {
  type Repr
  type Context
  def encoding: ValueEncoding[Repr]
  def capture(meta: EventMeta): F[Context]
  def contextFields(context: Context): Fields = Fields.empty
  def accepts(meta: EventMeta, context: Context): F[Boolean]
  def emit(event: EncodedEvent[Repr], context: Context): F[Unit]
  final private[experimental] def prepare(
      event: CapturedEvent,
      context: Context
  )(implicit F: Sync[F]): F[Delivery[F]] = F.defer(prepareOutput(event, context))

  protected def prepareOutput(
      event: CapturedEvent,
      context: Context
  )(implicit F: Sync[F]): F[Delivery[F]] =
    F.delay {
      EncodedEvent(event.meta, event.body.encode(encoding), event.fields.encode(encoding), event.cause)
    }.map(encoded => Delivery(F.defer(emit(encoded, context))))
}
