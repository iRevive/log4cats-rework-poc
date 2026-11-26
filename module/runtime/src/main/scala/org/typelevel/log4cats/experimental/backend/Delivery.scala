package org.typelevel.log4cats.experimental.backend

import cats.Applicative

final class Delivery[F[_]] private[experimental] (private val emit0: F[Unit]) {
  def deliver: F[Unit] = emit0
}

object Delivery {

  /** Wrap a prepared emission. Construct mutable backend builders inside fa, afresh on each delivery. */
  def apply[F[_]](fa: F[Unit]): Delivery[F]                                     = new Delivery(fa)
  private[experimental] def noop[F[_]](implicit F: Applicative[F]): Delivery[F] = apply(F.pure(()))
}
