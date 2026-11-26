package org.typelevel.log4cats.experimental.slf4j

import cats.effect.Sync
import cats.syntax.all._
import org.slf4j.{ILoggerFactory, LoggerFactory => JLoggerFactory}
import org.typelevel.log4cats.experimental.{Logger, LoggerFactory, Scope}
import org.typelevel.log4cats.experimental.context.ContextSource

object Slf4j {

  def factoryUnsafe[F[_]: Sync]: LoggerFactory[F] =
    factoryUnsafe(ContextSource.empty[F])

  def factoryUnsafe[F[_]: Sync](context: ContextSource[F]): LoggerFactory[F] =
    createFactory(JLoggerFactory.getILoggerFactory, context)

  def factory[F[_]: Sync]: F[LoggerFactory[F]] =
    factory(ContextSource.empty[F])

  def factory[F[_]: Sync](context: ContextSource[F]): F[LoggerFactory[F]] =
    Sync[F].delay(createFactory(JLoggerFactory.getILoggerFactory, context))

  def factory[F[_]](native: ILoggerFactory)(implicit F: Sync[F]): F[LoggerFactory[F]] =
    factory(native, ContextSource.empty[F])

  def factory[F[_]](native: ILoggerFactory, context: ContextSource[F])(implicit F: Sync[F]): F[LoggerFactory[F]] =
    F.pure(createFactory(native, context))

  private def createFactory[F[_]](native: ILoggerFactory, context: ContextSource[F])(implicit
      F: Sync[F]
  ): LoggerFactory[F] =
    new LoggerFactory[F] {
      def fromName(name: String): F[Logger[F]]     = F.delay(fromNameUnsafe(name))
      def fromClass(clazz: Class[_]): F[Logger[F]] = F.defer(fromName(clazz.getName))
      def fromNameUnsafe(name: String): Logger[F] =
        new Slf4jLogger[F](native.getLogger(Scope(name).name), context)
      def fromClassUnsafe(clazz: Class[_]): Logger[F] = fromNameUnsafe(clazz.getName)
    }
}
