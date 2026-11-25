package org.typelevel.log4cats.experimental

trait LoggerFactory[F[_]] {
  def fromName(name: String): F[Logger[F]]

  def fromClass(clazz: Class[_]): F[Logger[F]]

  def fromNameUnsafe(name: String): Logger[F]

  def fromClassUnsafe(clazz: Class[_]): Logger[F]
}

object LoggerFactory {
  def apply[F[_]](implicit factory: LoggerFactory[F]): LoggerFactory[F] = factory
}
