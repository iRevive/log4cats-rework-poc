package org.typelevel.log4cats.experimental.slf4j

import cats.effect.Sync
import cats.syntax.all._
import org.slf4j.{ILoggerFactory, LoggerFactory => JLoggerFactory}
import org.typelevel.log4cats.experimental.{
  Level,
  Logger,
  LoggerFactory,
  RecordWriter,
  Scope,
  ScopedLogger,
  ScopedLogging
}
import org.typelevel.log4cats.experimental.context.ContextSource

object Slf4j {

  final case class Config(
      native: ILoggerFactory = JLoggerFactory.getILoggerFactory,
      enablement: Enablement = Enablement.MdcAware,
      mdcPolicy: MdcPolicy = MdcPolicy.Isolate
  )

  /** Controls how logical MDC interacts with existing native thread MDC. */
  sealed trait MdcPolicy

  object MdcPolicy {

    /** Hide existing thread entries during MDC-aware checks and emission. */
    case object Isolate extends MdcPolicy

    /** Retain thread entries, with logical MDC winning collisions. */
    case object Overlay extends MdcPolicy
  }

  /** Selects which context is available during early native acceptance checks. */
  sealed trait Enablement

  object Enablement {

    /** Read logical context and install its MDC before checking. This is the default. */
    case object MdcAware extends Enablement

    /**
      * Call the native check with existing thread state, without reading logical context or installing
      * MDC. Providers may still run their native filters. Logical context is read only if enabled.
      */
    case object Native extends Enablement
  }

  def factory[F[_]: Sync]: LoggerFactory[F] =
    factory(Config())

  def factory[F[_]: Sync](config: Config): LoggerFactory[F] =
    factory(ContextSource.empty[F], config)

  def factory[F[_]](context: ContextSource[F], config: Config = Config())(implicit F: Sync[F]): LoggerFactory[F] =
    new LoggerFactory[F] {
      def fromName(name: String): F[Logger[F]] = F.delay(getLoggerFromName(name))
      def getLoggerFromName(name: String): Logger[F] =
        new Slf4jLogger[F](config.native.getLogger(Scope(name).name), context, config.enablement, config.mdcPolicy)
    }

  def scopedLogging[F[_]: Sync]: ScopedLogging[F] =
    scopedLogging(Config())

  def scopedLogging[F[_]: Sync](config: Config): ScopedLogging[F] =
    scopedLogging(ContextSource.empty[F], config)

  def scopedLogging[F[_]](context: ContextSource[F], config: Config = Config())(implicit
      F: Sync[F]
  ): ScopedLogging[F] = new ScopedLogging[F] {
    def use[A](name: String)(f: ScopedLogger => A): F[A] =
      F.delay(new Slf4jEmitter(config.native.getLogger(Scope(name).name), config.enablement, config.mdcPolicy))
        .flatMap { emitter =>
          F.defer(context.current).flatMap { captured =>
            F.delay {
              f(new ScopedLogger {
                def log(level: Level, eventName: Option[String], write: RecordWriter => Unit): Unit =
                  emitter.log(captured, level, eventName, write)
              })
            }
          }
        }
  }

}
