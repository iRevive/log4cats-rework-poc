package org.typelevel.log4cats.experimental

import org.typelevel.log4cats.experimental.Body
import org.typelevel.log4cats.experimental.encoding.Encoder

/** Immutable event description. Domain values are retained by reference until interpretation. */
final class Event private (
    private val eventBody: Option[Body],
    val fields: Fields,
    val cause: Option[Throwable],
    val mdc: Mdc
) {
  def withField[A](name: String, value: A)(implicit encoder: Encoder[A]): Event =
    new Event(eventBody, fields ++ Fields(Field(name, value)), cause, mdc)

  def withFields(other: Fields): Event = new Event(eventBody, fields ++ other, cause, mdc)

  def withCause(error: Throwable): Event = {
    require(error != null, "cause cannot be null")
    new Event(eventBody, fields, Some(error), mdc)
  }

  def withMdc(name: String, value: String): Event = withMdc(Mdc(name -> value))

  def withMdc(value: Mdc): Event = new Event(eventBody, fields, cause, mdc ++ value)

  def writeTo(writer: RecordWriter): Unit = {
    eventBody.foreach(body => writer.body(body))
    writer.fields(fields)
    writer.mdc(mdc)
    cause.foreach(writer.cause)
  }
}

object Event {
  def empty: Event = new Event(None, Fields.empty, None, Mdc.empty)

  def body[A](value: A)(implicit encoder: Encoder[A]): Event =
    new Event(Some(Body(value)), Fields.empty, None, Mdc.empty)

  def message(value: String): Event = body(value)

  def exception(error: Throwable): Event = {
    require(error != null, "cause cannot be null")
    new Event(None, Fields.empty, Some(error), Mdc.empty)
  }
}
