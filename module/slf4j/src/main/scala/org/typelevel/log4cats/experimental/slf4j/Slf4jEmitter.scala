package org.typelevel.log4cats.experimental.slf4j

import org.slf4j.{Logger, MDC}
import org.slf4j.event.{KeyValuePair, Level => JLevel}
import org.typelevel.log4cats.experimental.{Body, Fields, Level, Mdc, RecordWriter}
import org.typelevel.log4cats.experimental.context.LogContext
import org.typelevel.log4cats.experimental.internal.AccumulatingRecordWriter
import scala.collection.JavaConverters._

private[slf4j] final class Slf4jEmitter(
    native: Logger,
    enablement: Slf4j.Enablement,
    mdcPolicy: Slf4j.MdcPolicy
) {
  def accepts(level: Level, mdc: Mdc = Mdc.empty): Boolean = enablement match {
    case Slf4j.Enablement.Native => native.isEnabledForLevel(severity(level))
    case Slf4j.Enablement.MdcAware =>
      withMdc(mdc.values)(native.isEnabledForLevel(severity(level)))
  }

  def log(context: LogContext, level: Level, eventName: Option[String], write: RecordWriter => Unit): Unit =
    if (accepts(level, context.mdc)) {
      val record = AccumulatingRecordWriter.capture(write)
      prepare(level, eventName, record.body, context.fields ++ record.fields, record.cause, context.mdc ++ record.mdc)()
    }

  def prepare(
      level: Level,
      eventName: Option[String],
      body: Body,
      fields: Fields,
      cause: Option[Throwable],
      mdc: Mdc
  ): () => Unit = {
    val message = renderBody(body.encode(Slf4jEncoding.native))
    val encoded = encodeFields(fields, eventName)
    () => emitPrepared(level, eventName, message, encoded, cause, mdc)
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
  ): Unit = {
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
  ): Unit =
    withMdc(mdc.values) {
      // Builders and MDC installation stay within one synchronous emission, including replay.
      val builder = native.atLevel(severity(level)).setMessage(message)
      fields.foreach(field => builder.addKeyValue(field.key, field.value))
      eventName.foreach(name => builder.addKeyValue("event.name", name))
      cause.foreach(error => builder.setCause(error))
      builder.log()
    }

  private def withMdc[A](fields: Map[String, String])(use: => A): A = {
    val previous = MDC.getCopyOfContextMap
    try {
      mdcPolicy match {
        case Slf4j.MdcPolicy.Isolate => MDC.setContextMap(fields.asJava)
        case Slf4j.MdcPolicy.Overlay => fields.foreach { case (key, value) => MDC.put(key, value) }
      }
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
