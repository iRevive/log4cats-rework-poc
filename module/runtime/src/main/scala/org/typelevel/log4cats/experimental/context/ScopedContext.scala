package org.typelevel.log4cats.experimental.context

import cats.~>
import cats.effect.{IO, IOLocal}
import cats.syntax.all._

/** Fiber-local LogScope with separate diagnostic and structured channels. */
final class ScopedContext private (local: IOLocal[LogContext]) extends LogScope[IO] {
  def current: IO[LogContext] = local.get

  def scope(context: LogContext): IO ~> IO = updateContext(_ ++ context)

  def replaceContext(context: LogContext): IO ~> IO = updateContext(_ => context)

  private def updateContext(update: LogContext => LogContext): IO ~> IO = new (IO ~> IO) {
    def apply[A](fa: IO[A]): IO[A] = IO.uncancelable { poll =>
      local.get.flatMap(old => local.set(update(old)) *> poll(fa).guarantee(local.set(old)))
    }
  }
}

object ScopedContext {
  def create: IO[ScopedContext] = IOLocal(LogContext.empty).map(new ScopedContext(_))
}
