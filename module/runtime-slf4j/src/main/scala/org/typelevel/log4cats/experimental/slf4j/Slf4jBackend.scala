package org.typelevel.log4cats.experimental.slf4j

import cats.effect.Sync
import cats.syntax.all._
import org.slf4j.{ILoggerFactory, LoggerFactory => JLoggerFactory}
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
import org.typelevel.log4cats.experimental.context.ContextSource

object Slf4jBackend {
  def acquire[F[_]](implicit F: Sync[F]): F[Backend[F]] =
    F.delay(JLoggerFactory.getILoggerFactory).flatMap(factory => acquire[F](factory))

  def acquire[F[_]](factory: ILoggerFactory)(implicit F: Sync[F]): F[Backend[F]] =
    F.pure(new Backend[F] {
      def bind(scope: Scope): F[BoundBackend[F]] = F.delay(bindUnsafe(scope))
      def bindUnsafe(scope: Scope): BoundBackend[F] = {
        val logger = new Slf4jLogger[F](factory.getLogger(scope.name), ContextSource.empty[F])
        new BoundBackend[F] {
          type Context = Unit
          type Repr    = AnyRef
          val encoding: ValueEncoding[AnyRef]                     = Slf4jEncoding.native
          def capture(meta: EventMeta): F[Unit]                   = F.pure(())
          def accepts(meta: EventMeta, context: Unit): F[Boolean] = logger.isEnabled(meta.level, meta.eventName)
          override protected def prepareOutput(event: CapturedEvent, context: Unit)(implicit
              S: Sync[F]
          ): F[Delivery[F]] =
            S.map(logger.prepare(event.meta.level, event.meta.eventName, event.body, event.fields, event.cause))(
              Delivery(_)
            )
          def emit(event: EncodedEvent[AnyRef], context: Unit): F[Unit] =
            logger.emitEncoded(event.meta.level, event.meta.eventName, event.body, event.fields, event.cause)
        }
      }
    })
}
