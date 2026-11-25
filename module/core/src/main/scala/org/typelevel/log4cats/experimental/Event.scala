package org.typelevel.log4cats.experimental

import org.typelevel.log4cats.experimental.backend.Body
import org.typelevel.log4cats.experimental.encoding.Encoder

/** Immutable event description. Domain values are retained by reference until interpretation. */
final class Event private (
    private val eventBody: Option[Body],
    val fields: Fields,
    val cause: Option[Throwable]
) {
  def withField[A](name: String, value: A)(implicit encoder: Encoder[A]): Event =
    new Event(eventBody, fields ++ Fields(Field(name, value)), cause)

  def withFields(other: Fields): Event = new Event(eventBody, fields ++ other, cause)

  def withCause(error: Throwable): Event = {
    require(error != null, "cause cannot be null")
    new Event(eventBody, fields, Some(error))
  }

  def writeTo(writer: RecordWriter): Unit = {
    eventBody.foreach(body => writer.body(body))
    writer.fields(fields)
    cause.foreach(writer.cause)
  }
}

object Event {
  def empty: Event = new Event(None, Fields.empty, None)

  def body[A](value: A)(implicit encoder: Encoder[A]): Event =
    new Event(Some(Body(value)), Fields.empty, None)

  def message(value: String): Event = body(value)

  def exception(error: Throwable): Event = {
    require(error != null, "cause cannot be null")
    new Event(None, Fields.empty, Some(error))
  }
}
