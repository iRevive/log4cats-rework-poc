package org.typelevel.log4cats.experimental.context

import org.typelevel.log4cats.experimental.{Fields, Mdc}

object syntax {
  implicit final class LogScopeOps[F[_], A](private val fa: F[A]) extends AnyVal {

    /** Merge context around this effect. Nested inner scopes win collisions. */
    def scope(context: LogContext)(implicit logScope: LogScope[F]): F[A] =
      logScope.scope(context)(fa)

    /** Merge diagnostic context around this effect, preserving structured fields. */
    def withMdc(mdc: Mdc)(implicit logScope: LogScope[F]): F[A] =
      logScope.withMdc(mdc)(fa)

    /** Merge structured fields around this effect, preserving diagnostic context. */
    def withFields(fields: Fields)(implicit logScope: LogScope[F]): F[A] =
      logScope.withFields(fields)(fa)

    /** Replace both context channels around this effect; restore the previous context on exit. */
    def replaceContext(context: LogContext)(implicit logScope: LogScope[F]): F[A] =
      logScope.replaceContext(context)(fa)
  }
}
