package org.typelevel.log4cats.experimental.otel

import org.typelevel.log4cats.experimental.{Field, Fields, Mdc}

/** Mapping into OTel's single attribute namespace. Structured fields win collisions. */
sealed trait MdcMapping {
  private[experimental] def key(name: String): String
  private[experimental] final def fields(mdc: Mdc): Fields =
    Fields.from(mdc.values.iterator.map { case (name, value) => Field(key(name), value) }.toVector)
}

object MdcMapping {
  case object Flat extends MdcMapping {
    private[experimental] def key(name: String): String = name
  }

  /** The prefix is literal (include a separator, e.g. "mdc."). */
  final case class Prefixed(prefix: String) extends MdcMapping {
    require(prefix != null && prefix.nonEmpty, "MDC attribute prefix must be nonempty")
    private[experimental] def key(name: String): String = prefix + name
  }
}
