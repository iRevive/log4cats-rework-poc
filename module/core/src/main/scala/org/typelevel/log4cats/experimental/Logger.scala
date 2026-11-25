package org.typelevel.log4cats.experimental

import cats.~>

trait Logger[F[_]] extends LoggerMacro[F] {
  def log(level: Level, eventName: Option[String], write: RecordWriter => Unit): F[Unit]

  final def mapK[G[_]](fk: F ~> G): Logger[G] = {
    val underlying = this
    new Logger[G] {
      def log(level: Level, eventName: Option[String], write: RecordWriter => Unit): G[Unit] =
        fk(underlying.log(level, eventName, write))
    }
  }

}
