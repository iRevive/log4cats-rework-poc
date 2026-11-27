package org.typelevel.log4cats.experimental

trait LoggerFactory[F[_]] {
  def fromName(name: String): F[Logger[F]]

  def fromClass(clazz: Class[_]): F[Logger[F]] = fromName(clazz.getName)

  def getLoggerFromName(name: String): Logger[F]

  def getLoggerFromClass(clazz: Class[_]): Logger[F] = getLoggerFromName(clazz.getName)
}

object LoggerFactory {
  def apply[F[_]](implicit factory: LoggerFactory[F]): LoggerFactory[F] = factory
}
