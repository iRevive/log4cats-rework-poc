package org.typelevel.log4cats.experimental.context

import cats.effect.{IO, IOLocal}
import cats.syntax.all._
import org.typelevel.log4cats.experimental.{Fields, Mdc}

/** Fiber-local enrichment with separate diagnostic and structured channels. */
final class ScopedContext private (local: IOLocal[LogContext]) {
  def current: IO[LogContext]   = local.get
  def source: ContextSource[IO] = ContextSource(current)
  def scope[A](context: LogContext)(fa: IO[A]): IO[A] = IO.uncancelable { poll =>
    local.get.flatMap(old => local.set(old ++ context) *> poll(fa).guarantee(local.set(old)))
  }
  def withMdc[A](mdc: Mdc)(fa: IO[A]): IO[A]          = scope(LogContext(mdc = mdc))(fa)
  def withFields[A](fields: Fields)(fa: IO[A]): IO[A] = scope(LogContext(fields = fields))(fa)
}

object ScopedContext {
  def create: IO[ScopedContext] = IOLocal(LogContext.empty).map(new ScopedContext(_))
}
