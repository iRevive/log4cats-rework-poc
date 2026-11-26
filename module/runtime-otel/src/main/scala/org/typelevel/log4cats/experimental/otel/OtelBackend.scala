package org.typelevel.log4cats.experimental.otel

import cats.effect.{Async, Clock, Resource, Sync}
import cats.effect.std.Dispatcher
import cats.syntax.all._
import org.typelevel.log4cats.experimental.backend.{
  Backend,
  BoundBackend,
  CapturedEvent,
  Delivery,
  EncodedEvent,
  EventMeta
}
import org.typelevel.log4cats.experimental.encoding.ValueEncoding
import org.typelevel.log4cats.experimental.Scope
import org.typelevel.otel4s.logs.{Logger, LoggerProvider}
import org.typelevel.otel4s.AnyValue
import org.typelevel.vault.{Key, Vault}

object OtelBackend {
  def resource[F[_], Ctx](provider: LoggerProvider[F, Ctx])(implicit
      F: Async[F]
  ): Resource[F, Backend[F]] = resourceWithClock(provider, F)

  private[otel] def resourceWithClock[F[_], Ctx](provider: LoggerProvider[F, Ctx], clock: Clock[F])(implicit
      F: Async[F]
  ): Resource[F, Backend[F]] = Dispatcher.parallel[F].evalMap { dispatcher =>
    Key.newKey[F, Ctx].map { key =>
      new Backend[F] {
        def bind(scope: Scope): F[BoundBackend[F]]    = F.defer(provider.get(scope.name)).map(bound)
        def bindUnsafe(scope: Scope): BoundBackend[F] = bound(dispatcher.unsafeRunSync(provider.get(scope.name)))
        private def bound(native: Logger[F, Ctx]): BoundBackend[F] = {
          val logger = new OtelLogger[F, Ctx](native)(F, clock)
          new BoundBackend[F] {
            type Repr = AnyValue
            val encoding: ValueEncoding[AnyValue]  = AnyValueEncoding.anyValue
            def capture(meta: EventMeta): F[Vault] = logger.currentContext.map(ctx => Vault.empty.insert(key, ctx))
            def accepts(meta: EventMeta, attachments: Vault): F[Boolean] =
              F.defer(logger.isEnabled(meta.level, meta.eventName, requiredContext(attachments)))
            override protected def prepareOutput(event: CapturedEvent)(implicit S: Sync[F]): F[Delivery[F]] =
              S.map(
                S.defer(
                  logger.prepare(
                    event.meta.level,
                    event.meta.eventName,
                    event.meta.timestampNanos,
                    event.body,
                    event.fields,
                    event.cause,
                    requiredContext(event.attachments)
                  )
                )
              )(Delivery(_))
            def emit(event: EncodedEvent[AnyValue]): F[Unit] =
              F.defer(
                logger.emitEncoded(
                  event.meta.level,
                  event.meta.eventName,
                  event.meta.timestampNanos,
                  event.body,
                  event.fields,
                  event.cause,
                  requiredContext(event.attachments)
                )
              )
            private def requiredContext(attachments: Vault): Ctx = attachments.lookup(key).getOrElse {
              throw new IllegalStateException("OTel context attachment missing")
            }
          }
        }
      }
    }
  }
}
