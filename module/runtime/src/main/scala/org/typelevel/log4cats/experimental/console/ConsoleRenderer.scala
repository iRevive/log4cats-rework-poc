package org.typelevel.log4cats.experimental.console

import org.typelevel.log4cats.experimental.backend.EncodedEvent
import org.typelevel.log4cats.experimental.value.Value

object ConsoleRenderer {
  def render(event: EncodedEvent[Value]): String = {
    val fields = asObject(event.fields)
    val members = Vector(
      "level"          -> quote(event.meta.level.toString.toLowerCase(java.util.Locale.ROOT)),
      "scope"          -> quote(event.meta.scope.name),
      "timestampNanos" -> event.meta.timestampNanos.toString
    ) ++ event.meta.eventName.toVector.map(n => "eventName" -> quote(n)) ++
      Vector("fields" -> renderValue(Value.Obj(fields)), "body" -> renderValue(event.body)) ++
      event.cause.toVector.map(t => "throwable" -> quote(stackTrace(t)))
    "{" + members.map { case (k, v) => quote(k) + ":" + v }.mkString(",") + "}"
  }

  def renderValue(value: Value): String = value match {
    case Value.Empty                                      => "{\"$empty\":true}"
    case Value.Text(s)                                    => quote(s)
    case Value.Bool(b)                                    => b.toString
    case Value.Int64(n)                                   => n.toString
    case Value.Float64(d) if d.isNaN                      => quote("NaN")
    case Value.Float64(d) if d == Double.PositiveInfinity => quote("Infinity")
    case Value.Float64(d) if d == Double.NegativeInfinity => quote("-Infinity")
    case Value.Float64(d)                                 => java.lang.Double.toString(d)
    case Value.Bytes(bytes) =>
      "{\"$bytesBase64\":" + quote(java.util.Base64.getEncoder.encodeToString(bytes.toArray)) + "}"
    case Value.Arr(values) => values.map(renderValue).mkString("[", ",", "]")
    case Value.Obj(fields) =>
      fields.toSeq
        .sortBy(_._1)
        .map { case (k, v) => quote(k) + ":" + renderValue(v) }
        .mkString("{", ",", "}")
  }

  private def asObject(value: Value): Map[String, Value] = value match {
    case Value.Obj(values) => values
    case _                 => throw new IllegalArgumentException("encoded fields must be an object")
  }

  private def quote(s: String): String = {
    val out = new StringBuilder("\"")
    s.foreach {
      case '"'          => out.append("\\\"")
      case '\\'         => out.append("\\\\")
      case '\b'         => out.append("\\b")
      case '\f'         => out.append("\\f")
      case '\n'         => out.append("\\n")
      case '\r'         => out.append("\\r")
      case '\t'         => out.append("\\t")
      case c if c < ' ' => out.append("\\u%04x".format(c.toInt))
      case c            => out.append(c)
    }
    out.append('"').toString
  }

  private def stackTrace(t: Throwable): String = {
    val writer = new java.io.StringWriter()
    t.printStackTrace(new java.io.PrintWriter(writer))
    writer.toString
  }
}
