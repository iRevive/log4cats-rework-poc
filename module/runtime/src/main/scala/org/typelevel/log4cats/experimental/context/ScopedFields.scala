package org.typelevel.log4cats.experimental.context

import cats.effect.{IO, IOLocal}
import cats.syntax.all._
import org.typelevel.log4cats.experimental.Fields
import org.typelevel.vault.Vault

final class ScopedFields private (local: IOLocal[Fields]) {
  def current: IO[Fields] = local.get
  def scope[A](fields: Fields)(fa: IO[A]): IO[A] = IO.uncancelable { poll =>
    local.get.flatMap(old => local.set(old ++ fields) *> poll(fa).guarantee(local.set(old)))
  }
}

object ScopedFields {
  def create: IO[ScopedFields] = IOLocal(Fields.empty).map(new ScopedFields(_))
  def contextSource(
      scoped: ScopedFields,
      attachments: IO[Vault] = IO.pure(Vault.empty)
  ): ContextSource[IO] =
    ContextSource((scoped.current, attachments).mapN(LogContext.apply))
}
