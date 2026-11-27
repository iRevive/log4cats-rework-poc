package org.typelevel.log4cats.experimental.slf4j

import cats.effect.Sync
import org.slf4j.{ILoggerFactory, LoggerFactory => JLoggerFactory}
import org.typelevel.log4cats.experimental.{Logger, LoggerFactory, Scope}
import org.typelevel.log4cats.experimental.context.ContextSource

object Slf4j {

  def factory[F[_]: Sync]: LoggerFactory[F] =
    factory(ContextSource.empty[F])

  def factory[F[_]: Sync](context: ContextSource[F]): LoggerFactory[F] =
    createFactory(JLoggerFactory.getILoggerFactory, context)

  def factory[F[_]: Sync](native: ILoggerFactory): LoggerFactory[F] =
    factory(native, ContextSource.empty[F])

  def factory[F[_]: Sync](native: ILoggerFactory, context: ContextSource[F]): LoggerFactory[F] =
    createFactory(native, context)

  private def createFactory[F[_]](native: ILoggerFactory, context: ContextSource[F])(implicit
      F: Sync[F]
  ): LoggerFactory[F] =
    new LoggerFactory[F] {
      def fromName(name: String): F[Logger[F]] = F.delay(getLoggerFromName(name))
      def getLoggerFromName(name: String): Logger[F] =
        new Slf4jLogger[F](native.getLogger(Scope(name).name), context)
    }
}
