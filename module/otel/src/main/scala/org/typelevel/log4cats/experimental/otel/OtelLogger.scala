package org.typelevel.log4cats.experimental.otel

import cats.effect.{Clock, Sync}
import cats.syntax.all._
import org.typelevel.log4cats.experimental.context.ContextSource
import org.typelevel.log4cats.experimental.{Body, Fields, Level, Mdc, RecordWriter}
import org.typelevel.log4cats.experimental.encoding.FieldSink
import org.typelevel.log4cats.experimental.internal.AccumulatingRecordWriter
import org.typelevel.otel4s.logs.{Logger, Severity}
import org.typelevel.otel4s.{AnyValue, Attribute, AttributeKey}
import scala.concurrent.duration.FiniteDuration

private[experimental] final class OtelLogger[F[_], Ctx](
    native: Logger[F, Ctx],
    context: ContextSource[F],
    mdcMapping: MdcMapping = MdcMapping.Flat
)(implicit
    F: Sync[F],
    clock: Clock[F]
) extends org.typelevel.log4cats.experimental.Logger[F] {
  def log(level: Level, eventName: Option[String], write: RecordWriter => Unit): F[Unit] = F.defer {
    for {
      timestamp <- clock.realTime
      ctx       <- currentContext
      enabled   <- isEnabled(level, eventName, ctx)
      _ <-
        if (!enabled) F.unit
        else
          F.defer(context.current).flatMap { ambient =>
            F.delay(AccumulatingRecordWriter.capture(write)).flatMap { record =>
              prepare(
                level,
                eventName,
                timestamp.toNanos,
                record.body,
                ambient.fields ++ record.fields,
                record.cause,
                ctx,
                ambient.mdc ++ record.mdc
              ).flatten
            }
          }
    } yield ()
  }

  def currentContext: F[Ctx] = native.currentContext

  def isEnabled(level: Level, eventName: Option[String], ctx: Ctx): F[Boolean] =
    native.meta.isEnabled(ctx, Some(severity(level)), eventName)

  def prepare(
      level: Level,
      eventName: Option[String],
      timestampNanos: Long,
      body: Body,
      fields: Fields,
      cause: Option[Throwable],
      ctx: Ctx,
      mdc: Mdc
  ): F[F[Unit]] = F.delay {
    val encodedBody = body.encode(AnyValueEncoding.anyValue)
    val attributes  = Vector.newBuilder[Attribute[_]]
    val merged      = mdcMapping.fields(mdc) ++ fields
    val selected    = if (cause.isDefined) merged.filterNames(name => !reserved(name)) else merged
    selected.writeTo(
      AnyValueEncoding.anyValue,
      new FieldSink[AnyValue] {
        def put(name: String, value: AnyValue): Unit = attributes += AttributeKey.anyValue(name)(value)
      }
    )
    cause.foreach(error => attributes ++= exceptionAttributes(error))
    emitPrepared(level, eventName, timestampNanos, encodedBody, attributes.result(), ctx)
  }

  def emitEncoded(
      level: Level,
      eventName: Option[String],
      timestampNanos: Long,
      body: AnyValue,
      fields: AnyValue,
      cause: Option[Throwable],
      ctx: Ctx,
      mdc: Mdc
  ): F[Unit] = F.defer {
    val attributes = fields match {
      case values: AnyValue.MapValue =>
        values.value.toVector.collect {
          case (name, value) if cause.isEmpty || !reserved(name) =>
            AttributeKey.anyValue(name)(value): Attribute[_]
        }
      case _ => throw new IllegalStateException("encoded OTel fields are not an object")
    }
    val keys = attributes.map(_.key.name).toSet
    val diagnostic = mdc.values.toVector.collect {
      case (name, value) if !keys(mdcMapping.key(name)) && (cause.isEmpty || !reserved(mdcMapping.key(name))) =>
        AttributeKey.anyValue(mdcMapping.key(name))(AnyValue.string(value)): Attribute[_]
    }
    emitPrepared(
      level,
      eventName,
      timestampNanos,
      body,
      diagnostic ++ attributes ++ cause.toVector.flatMap(exceptionAttributes),
      ctx
    )
  }

  private def emitPrepared(
      level: Level,
      eventName: Option[String],
      timestampNanos: Long,
      body: AnyValue,
      attributes: Vector[Attribute[_]],
      ctx: Ctx
  ): F[Unit] = clock.realTime.flatMap { observedAt =>
    val base = native.logRecordBuilder
      .withContext(ctx)
      .withTimestamp(FiniteDuration(timestampNanos, java.util.concurrent.TimeUnit.NANOSECONDS))
      .withObservedTimestamp(observedAt)
      .withSeverity(severity(level))
      .withSeverityText(severityText(level))
      .withBody(body)
    val named = eventName.fold(base)(base.withEventName)
    named.addAttributes(attributes).emit
  }

  private val reserved = Set("exception.type", "exception.message", "exception.stacktrace")

  private def severity(level: Level): Severity = level match {
    case Level.Trace => Severity.trace
    case Level.Debug => Severity.debug
    case Level.Info  => Severity.info
    case Level.Warn  => Severity.warn
    case Level.Error => Severity.error
    case Level.Fatal => Severity.fatal
  }

  private def severityText(level: Level): String = level match {
    case Level.Trace => "TRACE"
    case Level.Debug => "DEBUG"
    case Level.Info  => "INFO"
    case Level.Warn  => "WARN"
    case Level.Error => "ERROR"
    case Level.Fatal => "FATAL"
  }

  private def exceptionAttributes(error: Throwable): Seq[Attribute[String]] = {
    val writer = new java.io.StringWriter()
    error.printStackTrace(new java.io.PrintWriter(writer))
    Seq(
      AttributeKey.string("exception.type")(error.getClass.getName),
      AttributeKey.string("exception.message")(Option(error.getMessage).getOrElse("")),
      AttributeKey.string("exception.stacktrace")(writer.toString)
    )
  }
}
