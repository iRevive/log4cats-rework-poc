package org.typelevel.log4cats.experimental.slf4j

import cats.effect.Sync
import cats.syntax.all._
import org.slf4j.Logger
import org.slf4j.event.{KeyValuePair, Level => JLevel}
import org.typelevel.log4cats.experimental.{Body, Fields, Level, RecordWriter}
import org.typelevel.log4cats.experimental.internal.AccumulatingRecordWriter
import scala.collection.JavaConverters._

private[experimental] final class Slf4jLogger[F[_]](native: Logger)(implicit F: Sync[F])
    extends org.typelevel.log4cats.experimental.Logger[F] {
  def log(level: Level, eventName: Option[String], write: RecordWriter => Unit): F[Unit] =
    isEnabled(level).flatMap {
      case false => F.unit
      case true =>
        F.delay(AccumulatingRecordWriter.capture(write)).flatMap { record =>
          prepare(level, eventName, record.body, record.fields, record.cause).flatten
        }
    }

  def isEnabled(level: Level): F[Boolean] = F.delay(native.isEnabledForLevel(severity(level)))

  def prepare(
      level: Level,
      eventName: Option[String],
      body: Body,
      fields: Fields,
      cause: Option[Throwable]
  ): F[F[Unit]] = F.delay {
    val message  = renderBody(body.encode(Slf4jEncoding.native))
    val values   = Vector.newBuilder[KeyValuePair]
    val selected = eventName.fold(fields)(_ => fields.filterNames(_ != "event.name"))
    selected.writeTo(
      Slf4jEncoding.native,
      new org.typelevel.log4cats.experimental.encoding.FieldSink[AnyRef] {
        def put(name: String, value: AnyRef): Unit = {
          values += new KeyValuePair(name, value)
          ()
        }
      }
    )
    emitPrepared(level, eventName, message, values.result(), cause)
  }

  def emitEncoded(
      level: Level,
      eventName: Option[String],
      body: AnyRef,
      fields: AnyRef,
      cause: Option[Throwable]
  ): F[Unit] = F.defer {
    val values = fields match {
      case values: java.util.Map[_, _] =>
        values.asScala.iterator.map { case (name, value) =>
          new KeyValuePair(name.asInstanceOf[String], value)
        }.toVector
      case _ => throw new IllegalArgumentException("encoded SLF4J fields must be an object")
    }
    val selected = if (eventName.isDefined) values.filterNot(_.key == "event.name") else values
    emitPrepared(level, eventName, renderBody(body), selected, cause)
  }

  private def emitPrepared(
      level: Level,
      eventName: Option[String],
      message: String,
      fields: Vector[KeyValuePair],
      cause: Option[Throwable]
  ): F[Unit] = F.blocking {
    // Builders are mutable and invocation-local, including during prepared replay.
    val builder = native.atLevel(severity(level)).setMessage(message)
    fields.foreach(field => builder.addKeyValue(field.key, field.value))
    eventName.foreach(name => builder.addKeyValue("event.name", name))
    cause.foreach(error => builder.setCause(error))
    builder.log()
  }

  private def renderBody(value: AnyRef): String = if (value == null) "" else value.toString

  private def severity(level: Level): JLevel = level match {
    case Level.Trace               => JLevel.TRACE
    case Level.Debug               => JLevel.DEBUG
    case Level.Info                => JLevel.INFO
    case Level.Warn                => JLevel.WARN
    case Level.Error | Level.Fatal => JLevel.ERROR
  }
}
