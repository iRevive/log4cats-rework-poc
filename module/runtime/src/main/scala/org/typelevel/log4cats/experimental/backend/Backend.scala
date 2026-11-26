package org.typelevel.log4cats.experimental.backend

import cats.Applicative
import org.typelevel.log4cats.experimental.Scope

trait Backend[F[_]] {
  def bind(scope: Scope): F[BoundBackend[F]]

  def bindUnsafe(scope: Scope): BoundBackend[F]
}

object Backend {
  def constant[F[_]](backend: BoundBackend[F])(implicit F: Applicative[F]): Backend[F] = new Backend[F] {
    def bind(scope: Scope): F[BoundBackend[F]]    = F.pure(backend)
    def bindUnsafe(scope: Scope): BoundBackend[F] = backend
  }
}
