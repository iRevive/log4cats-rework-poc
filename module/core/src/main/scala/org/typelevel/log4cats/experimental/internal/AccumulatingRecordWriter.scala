package org.typelevel.log4cats.experimental.internal

import org.typelevel.log4cats.experimental.Body
import org.typelevel.log4cats.experimental.encoding.Encoder
import org.typelevel.log4cats.experimental.{Field, Fields, Mdc, RecordWriter}
import scala.collection.mutable

private final class AccumulatingRecordWriter extends RecordWriter {
  private var currentMdc: Mdc                 = Mdc.empty
  private var open                            = true
  private var currentBody: Option[Body]       = None
  private val pendingFields                   = mutable.LinkedHashMap.empty[String, Field]
  private var currentCause: Option[Throwable] = None

  def body[A](value: A)(implicit encoder: Encoder[A]): Unit = body(Body(value))

  def body(value: Body): Unit = {
    checkOpen()
    currentBody = Some(value)
  }

  def field[A](name: String, value: A)(implicit encoder: Encoder[A]): Unit = {
    checkOpen()
    val next = Field(name, value)
    pendingFields.remove(next.name)
    pendingFields.update(next.name, next)
  }

  def fields(value: Fields): Unit = {
    checkOpen()
    value.values.foreach { next =>
      pendingFields.remove(next.name)
      pendingFields.update(next.name, next)
    }
  }

  def mdc(name: String, value: String): Unit = {
    checkOpen()
    mdc(Mdc(name -> value))
  }

  def mdc(value: Mdc): Unit = {
    checkOpen()
    currentMdc = currentMdc ++ value
  }

  def cause(error: Throwable): Unit = {
    checkOpen()
    require(error != null, "cause cannot be null")
    currentCause = Some(error)
  }

  private def checkOpen(): Unit =
    if (!open) throw new IllegalStateException("record writer is sealed")

  def finish(): RecordData = {
    checkOpen()
    open = false
    RecordData(
      currentBody.getOrElse(Body.empty),
      Fields.from(pendingFields.values.toVector),
      currentCause,
      currentMdc
    )
  }

  def close(): Unit = open = false
}

private[experimental] object AccumulatingRecordWriter {

  def capture(write: RecordWriter => Unit): RecordData = {
    val writer = new AccumulatingRecordWriter
    try {
      write(writer)
      writer.finish()
    } catch {
      case error: Throwable =>
        writer.close()
        throw error
    }
  }
}
