package org.typelevel.log4cats.experimental.otel

import cats.effect.{Async, Clock, Resource, Sync}
import cats.effect.std.Dispatcher
import cats.syntax.all._
import org.typelevel.log4cats.experimental.context.ContextSource
import org.typelevel.log4cats.experimental.backend.{
  Backend,
  BoundBackend,
  CapturedEvent,
  Delivery,
  EncodedEvent,
  EventMeta
}
import org.typelevel.log4cats.experimental.encoding.ValueEncoding
import org.typelevel.log4cats.experimental.{Fields, Scope}
import org.typelevel.otel4s.logs.{Logger, LoggerProvider}
import org.typelevel.otel4s.AnyValue

object OtelBackend {
  def resource[F[_], Ctx](provider: LoggerProvider[F, Ctx])(implicit F: Async[F]): Resource[F, Backend[F]] =
    resourceWithClock(provider, F)

  def resource[F[_], Ctx](
      provider: LoggerProvider[F, Ctx],
      fields: Ctx => Fields,
      mdcMapping: MdcMapping = MdcMapping.Flat
  )(implicit
      F: Async[F]
  ): Resource[F, Backend[F]] = resourceWithClock(provider, F, fields, mdcMapping)

  def resource[F[_], Ctx](provider: LoggerProvider[F, Ctx], mdcMapping: MdcMapping)(implicit
      F: Async[F]
  ): Resource[F, Backend[F]] =
    resourceWithClock(provider, F, mdcMapping = mdcMapping)

  private[otel] def resourceWithClock[F[_], Ctx](
      provider: LoggerProvider[F, Ctx],
      clock: Clock[F],
      fields: Ctx => Fields = (_: Ctx) => Fields.empty,
      mdcMapping: MdcMapping = MdcMapping.Flat
  )(implicit F: Async[F]): Resource[F, Backend[F]] = Dispatcher.parallel[F].map { dispatcher =>
    new Backend[F] {
      def bind(scope: Scope): F[BoundBackend[F]]    = provider.get(scope.name).map(bound)
      def bindUnsafe(scope: Scope): BoundBackend[F] = bound(dispatcher.unsafeRunSync(provider.get(scope.name)))
      private def bound(native: Logger[F, Ctx]): BoundBackend[F] = {
        val logger = new OtelLogger[F, Ctx](native, ContextSource.empty[F], mdcMapping)(F, clock)
        new BoundBackend[F] {
          type Repr    = AnyValue
          type Context = Ctx
          val encoding: ValueEncoding[AnyValue]            = AnyValueEncoding.anyValue
          def capture(meta: EventMeta): F[Ctx]             = logger.currentContext
          override def contextFields(context: Ctx): Fields = fields(context)
          def accepts(meta: EventMeta, context: Ctx): F[Boolean] =
            logger.isEnabled(meta.level, meta.eventName, context)
          override protected def prepareOutput(event: CapturedEvent, context: Ctx)(implicit
              S: Sync[F]
          ): F[Delivery[F]] =
            S.map(
              logger.prepare(
                event.meta.level,
                event.meta.eventName,
                event.meta.timestampNanos,
                event.body,
                event.fields,
                event.cause,
                context,
                event.mdc
              )
            )(Delivery(_))
          def emit(event: EncodedEvent[AnyValue], context: Ctx): F[Unit] =
            logger.emitEncoded(
              event.meta.level,
              event.meta.eventName,
              event.meta.timestampNanos,
              event.body,
              event.fields,
              event.cause,
              context,
              event.mdc
            )
        }
      }
    }
  }
}
