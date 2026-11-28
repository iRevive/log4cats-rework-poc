package org.typelevel.log4cats.experimental.slf4j

import cats.effect.Sync
import org.slf4j.{ILoggerFactory, LoggerFactory => JLoggerFactory}
import org.typelevel.log4cats.experimental.{Logger, LoggerFactory, Scope}
import org.typelevel.log4cats.experimental.context.ContextSource

object Slf4j {

  final case class Config(
      native: ILoggerFactory = JLoggerFactory.getILoggerFactory,
      enablement: Enablement = Enablement.MdcAware
  )

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
        new Slf4jLogger[F](config.native.getLogger(Scope(name).name), context, config.enablement)
    }
}
