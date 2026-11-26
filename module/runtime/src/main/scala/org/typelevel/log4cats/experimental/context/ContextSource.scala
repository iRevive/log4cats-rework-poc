package org.typelevel.log4cats.experimental.context

import cats.Applicative

trait ContextSource[F[_]] {
  def current: F[LogContext]
}

object ContextSource {
  def apply[F[_]](fa: F[LogContext]): ContextSource[F] = new ContextSource[F] {
    def current: F[LogContext] = fa
  }
  def empty[F[_]](implicit F: Applicative[F]): ContextSource[F] = apply(F.pure(LogContext()))
}
