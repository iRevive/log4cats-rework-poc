package org.typelevel.log4cats.experimental

import org.typelevel.log4cats.experimental.encoding.Encoder

private[experimental] trait LoggerMacro[F[_]] {
  self: Logger[F] =>

  inline final def logEvent(level: Level)(inline event: Event): F[Unit] =
    log(level, None, writer => event.writeTo(writer))

  inline final def logEvent(level: Level, eventName: Option[String])(
      inline event: Event
  ): F[Unit] =
    log(level, eventName, writer => event.writeTo(writer))

  inline final def trace(inline message: String): F[Unit] =
    log(Level.Trace, None, writer => writer.body[String](message)(Encoder.stringEncoder))

  inline final def trace(inline message: String, cause: Throwable): F[Unit] =
    log(
      Level.Trace,
      None,
      writer => {
        writer.body[String](message)(Encoder.stringEncoder)
        writer.cause(cause)
      }
    )

  inline final def debug(inline message: String): F[Unit] =
    log(Level.Debug, None, writer => writer.body[String](message)(Encoder.stringEncoder))

  inline final def debug(inline message: String, cause: Throwable): F[Unit] =
    log(
      Level.Debug,
      None,
      writer => {
        writer.body[String](message)(Encoder.stringEncoder)
        writer.cause(cause)
      }
    )

  inline final def info(inline message: String): F[Unit] =
    log(Level.Info, None, writer => writer.body[String](message)(Encoder.stringEncoder))

  inline final def info(inline message: String, cause: Throwable): F[Unit] =
    log(
      Level.Info,
      None,
      writer => {
        writer.body[String](message)(Encoder.stringEncoder)
        writer.cause(cause)
      }
    )

  inline final def warn(inline message: String): F[Unit] =
    log(Level.Warn, None, writer => writer.body[String](message)(Encoder.stringEncoder))

  inline final def warn(inline message: String, cause: Throwable): F[Unit] =
    log(
      Level.Warn,
      None,
      writer => {
        writer.body[String](message)(Encoder.stringEncoder)
        writer.cause(cause)
      }
    )

  inline final def error(inline message: String): F[Unit] =
    log(Level.Error, None, writer => writer.body[String](message)(Encoder.stringEncoder))

  inline final def error(inline message: String, cause: Throwable): F[Unit] =
    log(
      Level.Error,
      None,
      writer => {
        writer.body[String](message)(Encoder.stringEncoder)
        writer.cause(cause)
      }
    )

  inline final def fatal(inline message: String): F[Unit] =
    log(Level.Fatal, None, writer => writer.body[String](message)(Encoder.stringEncoder))

  inline final def fatal(inline message: String, cause: Throwable): F[Unit] =
    log(
      Level.Fatal,
      None,
      writer => {
        writer.body[String](message)(Encoder.stringEncoder)
        writer.cause(cause)
      }
    )

  inline final def traceEvent(inline event: Event): F[Unit] =
    logEvent(Level.Trace)(event)

  inline final def traceEvent(name: String)(inline event: Event): F[Unit] =
    log(Level.Trace, Some(name), writer => event.writeTo(writer))

  inline final def debugEvent(inline event: Event): F[Unit] =
    logEvent(Level.Debug)(event)

  inline final def debugEvent(name: String)(inline event: Event): F[Unit] =
    log(Level.Debug, Some(name), writer => event.writeTo(writer))

  inline final def infoEvent(inline event: Event): F[Unit] =
    logEvent(Level.Info)(event)

  inline final def infoEvent(name: String)(inline event: Event): F[Unit] =
    log(Level.Info, Some(name), writer => event.writeTo(writer))

  inline final def warnEvent(inline event: Event): F[Unit] =
    logEvent(Level.Warn)(event)

  inline final def warnEvent(name: String)(inline event: Event): F[Unit] =
    log(Level.Warn, Some(name), writer => event.writeTo(writer))

  inline final def errorEvent(inline event: Event): F[Unit] =
    logEvent(Level.Error)(event)

  inline final def errorEvent(name: String)(inline event: Event): F[Unit] =
    log(Level.Error, Some(name), writer => event.writeTo(writer))

  inline final def fatalEvent(inline event: Event): F[Unit] =
    logEvent(Level.Fatal)(event)

  inline final def fatalEvent(name: String)(inline event: Event): F[Unit] =
    log(Level.Fatal, Some(name), writer => event.writeTo(writer))

}
