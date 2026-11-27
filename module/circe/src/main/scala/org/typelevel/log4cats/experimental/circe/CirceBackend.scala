package org.typelevel.log4cats.experimental.circe

import cats.Applicative
import io.circe.Json
import org.typelevel.log4cats.experimental.backend.{Backend, BoundBackend, EncodedEvent, EventMeta}
import org.typelevel.log4cats.experimental.encoding.ValueEncoding

object CirceBackend {
  def apply[F[_]](write: EncodedEvent[Json] => F[Unit])(implicit F: Applicative[F]): Backend[F] =
    Backend.constant(new BoundBackend[F] {
      type Context = Unit
      type Repr    = Json
      val encoding: ValueEncoding[Json]                           = JsonEncoding.json
      def capture(meta: EventMeta): F[Unit]                       = F.pure(())
      def accepts(meta: EventMeta, context: Unit): F[Boolean]     = F.pure(true)
      def emit(event: EncodedEvent[Json], context: Unit): F[Unit] = write(event)
    })
}
