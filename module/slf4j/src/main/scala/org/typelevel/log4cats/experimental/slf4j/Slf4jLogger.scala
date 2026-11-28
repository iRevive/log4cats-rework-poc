package org.typelevel.log4cats.experimental.slf4j

import cats.effect.Sync
import cats.syntax.all._
import org.typelevel.log4cats.experimental.context.{ContextSource, LogContext}
import org.slf4j.Logger
import org.typelevel.log4cats.experimental.{Body, Fields, Level, Mdc, RecordWriter}
import org.typelevel.log4cats.experimental.internal.AccumulatingRecordWriter

private[experimental] final class Slf4jLogger[F[_]](
    native: Logger,
    context: ContextSource[F],
    enablement: Slf4j.Enablement = Slf4j.Enablement.MdcAware,
    mdcPolicy: Slf4j.MdcPolicy = Slf4j.MdcPolicy.Isolate
)(implicit F: Sync[F])
    extends org.typelevel.log4cats.experimental.Logger[F] {
  private val emitter = new Slf4jEmitter(native, enablement, mdcPolicy)

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

  def accepts(level: Level, mdc: Mdc = Mdc.empty): F[Boolean] = F.delay(emitter.accepts(level, mdc))

  private def nativeCheck(level: Level): F[Boolean] = F.delay(emitter.accepts(level))

  def prepare(
      level: Level,
      eventName: Option[String],
      body: Body,
      fields: Fields,
      cause: Option[Throwable],
      mdc: Mdc
  ): F[F[Unit]] =
    F.delay(emitter.prepare(level, eventName, body, fields, cause, mdc)).map(emit => F.delay(emit()))

  def emitEncoded(
      level: Level,
      eventName: Option[String],
      body: AnyRef,
      fields: AnyRef,
      cause: Option[Throwable],
      mdc: Mdc
  ): F[Unit] = F.delay(emitter.emitEncoded(level, eventName, body, fields, cause, mdc))
}
