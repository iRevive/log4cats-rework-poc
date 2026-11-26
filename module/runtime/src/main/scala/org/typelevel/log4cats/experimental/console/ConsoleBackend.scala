package org.typelevel.log4cats.experimental.console

import cats.effect.Sync
import cats.syntax.all._
import org.typelevel.log4cats.experimental.backend.{Backend, EventMeta}
import org.typelevel.log4cats.experimental.value.ValueBackend

object ConsoleBackend {
  def apply[F[_]](
      writeLine: String => F[Unit],
      capture: F[Unit],
      accepts: (EventMeta, Unit) => F[Boolean]
  )(implicit
      F: Sync[F]
  ): Backend[F] = ValueBackend(
    event => F.defer(F.delay(ConsoleRenderer.render(event)).flatMap(writeLine)),
    capture,
    accepts
  )

  def stdout[F[_]: Sync]: Backend[F] = apply(
    line => Sync[F].delay(println(line)),
    Sync[F].pure(()),
    (_, _) => Sync[F].pure(true)
  )
}
