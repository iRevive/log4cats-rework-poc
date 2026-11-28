package org.typelevel.log4cats.experimental.slf4j

import cats.effect.Sync
import cats.syntax.all._
import org.typelevel.log4cats.experimental.context.{ContextSource, LogContext}
import org.slf4j.{Logger, MDC}
import org.slf4j.event.{KeyValuePair, Level => JLevel}
import org.typelevel.log4cats.experimental.{Body, Fields, Level, Mdc, RecordWriter}
import org.typelevel.log4cats.experimental.internal.AccumulatingRecordWriter
import scala.collection.JavaConverters._

private[experimental] final class Slf4jLogger[F[_]](
    native: Logger,
    context: ContextSource[F],
    enablement: Slf4j.Enablement = Slf4j.Enablement.MdcAware
)(implicit F: Sync[F])
    extends org.typelevel.log4cats.experimental.Logger[F] {
  def log(level: Level, eventName: Option[String], write: RecordWriter => Unit): F[Unit] = {
    val accepted = enablement match {
      case Slf4j.Enablement.Native =>
        nativeCheck(level).flatMap {
          case false => F.pure(Option.empty[LogContext])
          case true  => F.defer(context.current).map(data => Some(data): Option[LogContext])
        }
      case Slf4j.Enablement.MdcAware =>
        F.defer(context.current).flatMap { data =>
          accepts(level, data.mdc).map(enabled => if (enabled) Some(data) else None)
        }
    }
    accepted.flatMap {
      case None => F.unit
      case Some(ambient) =>
        F.delay(AccumulatingRecordWriter.capture(write)).flatMap { record =>
          prepare(
            level,
            eventName,
            record.body,
            ambient.fields ++ record.fields,
            record.cause,
            ambient.mdc ++ record.mdc
          ).flatten
        }
    }
  }

  def accepts(level: Level, mdc: Mdc = Mdc.empty): F[Boolean] = enablement match {
    case Slf4j.Enablement.Native => nativeCheck(level)
    case Slf4j.Enablement.MdcAware =>
      F.delay(withMdc(mdc.values)(native.isEnabledForLevel(severity(level))))
  }

  private def nativeCheck(level: Level): F[Boolean] = F.delay(native.isEnabledForLevel(severity(level)))

  def prepare(
      level: Level,
      eventName: Option[String],
      body: Body,
      fields: Fields,
      cause: Option[Throwable],
      mdc: Mdc
  ): F[F[Unit]] = F.delay {
    val message = renderBody(body.encode(Slf4jEncoding.native))
    emitPrepared(level, eventName, message, encodeFields(fields, eventName), cause, mdc)
  }

  private def encodeFields(fields: Fields, eventName: Option[String]): Vector[KeyValuePair] = {
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
    values.result()
  }

  def emitEncoded(
      level: Level,
      eventName: Option[String],
      body: AnyRef,
      fields: AnyRef,
      cause: Option[Throwable],
      mdc: Mdc
  ): F[Unit] = F.defer {
    val values = fields match {
      case values: java.util.Map[_, _] =>
        values.asScala.iterator.map { case (name, value) =>
          new KeyValuePair(name.asInstanceOf[String], value)
        }.toVector
      case _ => throw new IllegalArgumentException("encoded SLF4J fields must be an object")
    }
    val selected = if (eventName.isDefined) values.filterNot(_.key == "event.name") else values
    emitPrepared(level, eventName, renderBody(body), selected, cause, mdc)
  }

  private def emitPrepared(
      level: Level,
      eventName: Option[String],
      message: String,
      fields: Vector[KeyValuePair],
      cause: Option[Throwable],
      mdc: Mdc
  ): F[Unit] =
    F.delay {
      withMdc(mdc.values) {
        // Builders and MDC installation stay within one synchronous emission, including replay.
        val builder = native.atLevel(severity(level)).setMessage(message)
        fields.foreach(field => builder.addKeyValue(field.key, field.value))
        eventName.foreach(name => builder.addKeyValue("event.name", name))
        cause.foreach(error => builder.setCause(error))
        builder.log()
      }
    }

  private def withMdc[A](fields: Map[String, String])(use: => A): A = {
    val previous = MDC.getCopyOfContextMap
    try {
      MDC.setContextMap(fields.asJava)
      use
    } finally
      if (previous == null) MDC.clear() else MDC.setContextMap(previous)
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
