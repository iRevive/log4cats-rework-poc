package org.typelevel.log4cats.experimental.context

import cats.~>
import cats.mtl.Local
import org.typelevel.log4cats.experimental.{Fields, Mdc}

/**
  * Read and scope ambient logging context. All loggers using this capability's source observe
  * the same scopes. Implementations must restore the previous context on scope exit.
  */
trait LogScope[F[_]] {
  def current: F[LogContext]

  /** Merge context for the duration of an effect; scoped values win collisions in each channel. */
  def scope(context: LogContext): F ~> F

  /** Replace both channels for the duration of an effect, restoring the previous context on exit. */
  def replaceContext(context: LogContext): F ~> F

  final def source: ContextSource[F] = ContextSource(current)

  final def withMdc(mdc: Mdc): F ~> F = scope(LogContext(mdc = mdc))

  final def withFields(fields: Fields): F ~> F = scope(LogContext(fields = fields))
}

object LogScope {

  def apply[F[_]](implicit scope: LogScope[F]): LogScope[F] = scope

  /** Wrap an existing carrier without allocating a new one or running an effect. */
  def fromLocal[F[_]](implicit local: Local[F, LogContext]): LogScope[F] = new LogScope[F] {
    def current: F[LogContext] = local.ask[LogContext]

    def scope(context: LogContext): F ~> F = new (F ~> F) {
      def apply[A](fa: F[A]): F[A] = local.local(fa)(current => current ++ context)
    }

    def replaceContext(context: LogContext): F ~> F = new (F ~> F) {
      def apply[A](fa: F[A]): F[A] = local.local(fa)(_ => context)
    }
  }
}
