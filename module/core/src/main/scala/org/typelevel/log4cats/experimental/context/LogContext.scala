package org.typelevel.log4cats.experimental.context

import org.typelevel.log4cats.experimental.{Fields, Mdc}

/** Enrichment channels are independent of whether values are scoped or supplied per event. */
final case class LogContext(mdc: Mdc = Mdc.empty, fields: Fields = Fields.empty) {
  def ++(other: LogContext): LogContext = LogContext(mdc ++ other.mdc, fields ++ other.fields)
}

object LogContext {
  val empty: LogContext = LogContext()
}
