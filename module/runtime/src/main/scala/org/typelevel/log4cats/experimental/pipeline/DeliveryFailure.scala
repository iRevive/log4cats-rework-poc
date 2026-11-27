package org.typelevel.log4cats.experimental.pipeline

import org.typelevel.log4cats.experimental.{Level, Scope}

/** A sink delivery error. The event payload is deliberately excluded from diagnostics. */
final case class DeliveryFailure(level: Level, scope: Scope, eventName: Option[String], error: Throwable)
