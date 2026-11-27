package org.typelevel.log4cats.experimental

/** Immutable diagnostic context. Values are strings; structured data belongs in Fields. */
final class Mdc private (val values: Map[String, String]) {
  def ++(other: Mdc): Mdc =
    if (other.isEmpty) this else if (isEmpty) other else new Mdc(values ++ other.values)
  def isEmpty: Boolean = values.isEmpty
}

object Mdc {
  val empty: Mdc                             = new Mdc(Map.empty)
  def apply(entries: (String, String)*): Mdc = from(entries.toMap)
  def from(entries: Map[String, String]): Mdc = {
    entries.foreach { case (name, value) =>
      Field.validateName(name)
      require(value != null, "MDC values cannot be null")
    }
    if (entries.isEmpty) empty else new Mdc(entries)
  }
}
