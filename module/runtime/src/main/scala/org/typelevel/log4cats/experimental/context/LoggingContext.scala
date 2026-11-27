package org.typelevel.log4cats.experimental.context

import cats.mtl.Local
import org.typelevel.log4cats.experimental.Fields

object LoggingContext {

  def withFields[F[_], A](fields: Fields)(fa: F[A])(implicit local: Local[F, Fields]): F[A] =
    local.local(fa)(current => current ++ fields)
}
