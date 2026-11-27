package org.typelevel.log4cats.experimental.pipeline

import cats.effect.Sync
import cats.syntax.all._
import org.typelevel.log4cats.experimental.backend.Delivery
import org.typelevel.log4cats.experimental.pipeline.internal.RecordPreparer
import org.typelevel.log4cats.experimental.{Level, Logger, LoggerFactory, RecordWriter, Scope}

final class LoggingRuntime[F[_]] private[pipeline] (
    bind: Scope => F[RecordPreparer[F]],
    bindUnsafe: Scope => RecordPreparer[F],
    val flush: F[Unit]
)(implicit F: Sync[F]) extends LoggerFactory[F] {
  def fromName(name: String): F[Logger[F]] = F.defer(bind(Scope(name))).map(logger)

  def getLoggerFromName(name: String): Logger[F] = logger(bindUnsafe(Scope(name)))

  def prepare(scope: Scope, level: Level, eventName: Option[String])(
      write: RecordWriter => Unit
  ): F[Delivery[F]] = F.defer(bind(scope)).flatMap(_.prepare(level, eventName)(write))

  private def logger(preparer: RecordPreparer[F]): Logger[F] = new Logger[F] {
    def log(level: Level, eventName: Option[String], write: RecordWriter => Unit): F[Unit] =
      preparer.prepare(level, eventName)(write).flatMap(_.deliver)
  }
}
