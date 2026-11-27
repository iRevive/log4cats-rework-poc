package org.typelevel.log4cats.experimental.otel

import cats.effect.{Async, Resource}
import cats.effect.std.Dispatcher
import cats.syntax.all._
import org.typelevel.log4cats.experimental.context.ContextSource
import org.typelevel.log4cats.experimental.{Logger, LoggerFactory, Scope}
import org.typelevel.otel4s.logs.LoggerProvider

object Otel {
  def resource[F[_], Ctx](provider: LoggerProvider[F, Ctx])(implicit
      F: Async[F]
  ): Resource[F, LoggerFactory[F]] = resource(provider, ContextSource.empty[F])

  def resource[F[_], Ctx](
      provider: LoggerProvider[F, Ctx],
      context: ContextSource[F],
      mdcMapping: MdcMapping = MdcMapping.Flat
  )(implicit
      F: Async[F]
  ): Resource[F, LoggerFactory[F]] = Dispatcher.parallel[F].map { dispatcher =>
    new LoggerFactory[F] {
      def fromName(name: String): F[Logger[F]] =
        provider.get(Scope(name).name).map(native => new OtelLogger[F, Ctx](native, context, mdcMapping)(F, F))
      def getLoggerFromName(name: String): Logger[F] =
        new OtelLogger[F, Ctx](dispatcher.unsafeRunSync(provider.get(Scope(name).name)), context, mdcMapping)(F, F)
    }
  }
}
