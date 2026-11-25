package org.typelevel.log4cats.experimental.circe

import cats.Applicative
import io.circe.Json
import org.typelevel.log4cats.experimental.backend.{Backend, BoundBackend, EncodedEvent, EventMeta}
import org.typelevel.log4cats.experimental.encoding.ValueEncoding
import org.typelevel.vault.Vault

object CirceBackend {
  def apply[F[_]](write: EncodedEvent[Json] => F[Unit])(implicit F: Applicative[F]): Backend[F] =
    Backend.constant(new BoundBackend[F] {
      type Repr = Json
      val encoding: ValueEncoding[Json]                            = JsonEncoding.json
      def capture(meta: EventMeta): F[Vault]                       = F.pure(Vault.empty)
      def accepts(meta: EventMeta, attachments: Vault): F[Boolean] = F.pure(true)
      def emit(event: EncodedEvent[Json]): F[Unit]                 = write(event)
    })
}
