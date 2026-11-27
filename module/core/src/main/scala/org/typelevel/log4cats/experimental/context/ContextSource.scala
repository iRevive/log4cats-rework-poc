package org.typelevel.log4cats.experimental.context

import cats.{Applicative, FlatMap, Functor}
import cats.mtl.Ask
import org.typelevel.log4cats.experimental.Fields

trait ContextSource[F[_]] { self =>
  def current: F[Fields]

  final def ++(other: ContextSource[F])(implicit F: FlatMap[F]): ContextSource[F] = new ContextSource[F] {
    def current: F[Fields] = F.flatMap(self.current)(left => F.map(other.current)(left ++ _))
  }

  final def map(f: Fields => Fields)(implicit F: Functor[F]): ContextSource[F] = new ContextSource[F] {
    def current: F[Fields] = F.map(self.current)(f)
  }
}

object ContextSource {
  def apply[F[_]](fa: F[Fields]): ContextSource[F] = new ContextSource[F] {
    def current: F[Fields] = fa
  }
  def const[F[_]](fields: Fields)(implicit F: Applicative[F]): ContextSource[F] = apply(F.pure(fields))

  def fromAsk[F[_]](ask: Ask[F, Fields]): ContextSource[F] = apply(ask.ask)

  def empty[F[_]](implicit F: Applicative[F]): ContextSource[F] = const(Fields.empty)
}
