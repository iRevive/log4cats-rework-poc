package org.typelevel.log4cats.experimental.pipeline.internal

import org.typelevel.log4cats.experimental.backend.Delivery
import org.typelevel.log4cats.experimental.{Level, RecordWriter}

private[pipeline] trait RecordPreparer[F[_]] {
  def prepare(level: Level, eventName: Option[String])(write: RecordWriter => Unit): F[Delivery[F]]
}
