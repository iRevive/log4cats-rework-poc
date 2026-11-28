package org.typelevel.log4cats.experimental

import cats.~>

/** Suspend a synchronous computation with a logger and one captured ambient context. */
trait ScopedLogging[F[_]] {

  /**
    * Run the callback synchronously inside F. The supplied logger must not escape the callback.
    * Callback and logging failures propagate; already emitted records remain emitted.
    */
  def use[A](name: String)(f: ScopedLogger => A): F[A]

  final def use[A](clazz: Class[_])(f: ScopedLogger => A): F[A] = use(clazz.getName)(f)

  final def mapK[G[_]](fk: F ~> G): ScopedLogging[G] = {
    val underlying = this
    new ScopedLogging[G] {
      def use[A](name: String)(f: ScopedLogger => A): G[A] = fk(underlying.use(name)(f))
    }
  }
}

object ScopedLogging {
  def apply[F[_]](implicit logging: ScopedLogging[F]): ScopedLogging[F] = logging
}
