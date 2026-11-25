package org.typelevel.log4cats.experimental

import org.typelevel.log4cats.experimental.encoding.{FieldSink, ValueEncoding}

final class Fields private (private val entries: Vector[Field]) {
  def ++(other: Fields): Fields =
    if (other.isEmpty) this else if (isEmpty) other else Fields.from(entries ++ other.entries)
  def isEmpty: Boolean                = entries.isEmpty
  def contains(name: String): Boolean = entries.exists(_.name == name)
  def filterNames(keep: String => Boolean): Fields = {
    val kept = entries.filter(f => keep(f.name))
    if (kept.isEmpty) Fields.empty else new Fields(kept)
  }
  def encode[V](out: ValueEncoding[V]): V = {
    val b = out.obj()
    entries.foreach(f => b.put(f.name, f.encode(out)))
    b.result()
  }
  def writeTo[V](out: ValueEncoding[V], sink: FieldSink[V]): Unit =
    entries.foreach(f => sink.put(f.name, f.encode(out)))
  private[experimental] def values: Vector[Field] = entries
}

object Fields {
  val empty: Fields                                     = new Fields(Vector.empty)
  def apply(fields: Field*): Fields                     = from(fields.toVector)
  def omit(omitField: Boolean, field: => Field): Fields = if (omitField) empty else Fields(field)
  private[experimental] def from(fields: Vector[Field]): Fields = {
    val winners = scala.collection.mutable.LinkedHashMap.empty[String, Field]
    fields.foreach { f =>
      Field.validateName(f.name)
      winners.remove(f.name)
      winners.update(f.name, f)
    }
    if (winners.isEmpty) empty else new Fields(winners.values.toVector)
  }
}
