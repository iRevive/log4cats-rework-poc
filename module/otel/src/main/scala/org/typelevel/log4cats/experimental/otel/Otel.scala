package org.typelevel.log4cats.experimental.otel

import cats.effect.{Async, Resource}
import cats.effect.std.Dispatcher
import cats.syntax.all._
import org.typelevel.log4cats.experimental.{Logger, LoggerFactory, Scope}
import org.typelevel.otel4s.logs.LoggerProvider

object Otel {
  def resource[F[_], Ctx](provider: LoggerProvider[F, Ctx])(implicit
      F: Async[F]
  ): Resource[F, LoggerFactory[F]] = Dispatcher.parallel[F].map { dispatcher =>
    new LoggerFactory[F] {
      def fromName(name: String): F[Logger[F]] =
        F.defer(provider.get(Scope(name).name)).map(native => new OtelLogger[F, Ctx](native)(F, F))
      def fromClass(clazz: Class[_]): F[Logger[F]] = F.defer(fromName(clazz.getName))
      def fromNameUnsafe(name: String): Logger[F] =
        new OtelLogger[F, Ctx](dispatcher.unsafeRunSync(provider.get(Scope(name).name)))(F, F)
      def fromClassUnsafe(clazz: Class[_]): Logger[F] = fromNameUnsafe(clazz.getName)
    }
  }
}
