package org.typelevel.log4cats.experimental.context

import cats.{Applicative, FlatMap, Functor}
import cats.mtl.Ask
import org.typelevel.log4cats.experimental.{Fields, Mdc}

trait ContextSource[F[_]] { self =>
  def current: F[LogContext]

  final def ++(other: ContextSource[F])(implicit F: FlatMap[F]): ContextSource[F] = new ContextSource[F] {
    def current: F[LogContext] = F.flatMap(self.current)(left => F.map(other.current)(left ++ _))
  }

  final def map(f: LogContext => LogContext)(implicit F: Functor[F]): ContextSource[F] = new ContextSource[F] {
    def current: F[LogContext] = F.map(self.current)(f)
  }
}

object ContextSource {
  def apply[F[_]](fa: F[LogContext]): ContextSource[F] = new ContextSource[F] {
    def current: F[LogContext] = fa
  }
  def fromFields[F[_]: Functor](fa: F[Fields]): ContextSource[F] = apply(
    implicitly[Functor[F]].map(fa)(f => LogContext(fields = f))
  )
  def fromMdc[F[_]: Functor](fa: F[Mdc]): ContextSource[F] = apply(
    implicitly[Functor[F]].map(fa)(m => LogContext(mdc = m))
  )
  def const[F[_]: Applicative](context: LogContext): ContextSource[F] = apply(implicitly[Applicative[F]].pure(context))
  def const[F[_]: Applicative](fields: Fields): ContextSource[F]      = const(LogContext(fields = fields))
  def fromAsk[F[_]: Functor](ask: Ask[F, Fields]): ContextSource[F]   = fromFields(ask.ask)
  def fromContextAsk[F[_]](ask: Ask[F, LogContext]): ContextSource[F] = apply(ask.ask)
  def empty[F[_]: Applicative]: ContextSource[F]                      = const(LogContext.empty)
}
