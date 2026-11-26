package org.typelevel.log4cats.experimental.context

import cats.Functor
import cats.mtl.{Ask, Local}
import org.typelevel.log4cats.experimental.Fields

object LoggingContext {

  def fromAsk[F[_]](ask: Ask[F, Fields])(implicit F: Functor[F]): ContextSource[F] =
    new ContextSource[F] {
      def current: F[LogContext] = F.map(ask.ask)(fields => LogContext(fields = fields))
    }

  def withFields[F[_], A](fields: Fields)(fa: F[A])(implicit local: Local[F, Fields]): F[A] =
    local.local(fa)(current => current ++ fields)
}
