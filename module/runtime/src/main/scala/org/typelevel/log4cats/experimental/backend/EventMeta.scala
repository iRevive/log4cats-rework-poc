package org.typelevel.log4cats.experimental.backend

import org.typelevel.log4cats.experimental.{Level, Scope}

final case class EventMeta(
    level: Level,
    scope: Scope,
    eventName: Option[String],
    timestampNanos: Long
)
