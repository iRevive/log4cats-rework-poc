package org.typelevel.log4cats.experimental.context

import cats.mtl.Local
import org.typelevel.log4cats.experimental.{Fields, Mdc}

object LoggingContext {
  def withContext[F[_], A](context: LogContext)(fa: F[A])(implicit local: Local[F, LogContext]): F[A] =
    local.local(fa)(current => current ++ context)

  def withMdc[F[_], A](mdc: Mdc)(fa: F[A])(implicit local: Local[F, LogContext]): F[A] =
    withContext(LogContext(mdc = mdc))(fa)

  def withFields[F[_], A](fields: Fields)(fa: F[A])(implicit local: Local[F, Fields]): F[A] =
    local.local(fa)(current => current ++ fields)
}
