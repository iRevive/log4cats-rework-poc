package org.typelevel.log4cats.experimental.slf4j

import cats.effect.Sync
import cats.syntax.all._
import org.slf4j.event.{KeyValuePair, Level => JLevel}
import org.slf4j.{ILoggerFactory, Logger, LoggerFactory => JLoggerFactory}
import org.typelevel.log4cats.experimental.backend.{
  Backend,
  BoundBackend,
  CapturedEvent,
  Delivery,
  EncodedEvent,
  EventMeta
}
import org.typelevel.log4cats.experimental.encoding.{FieldSink, ValueEncoding}
import org.typelevel.log4cats.experimental.{Level, Scope}
import org.typelevel.vault.Vault
import scala.collection.JavaConverters._

object Slf4jBackend {
  def acquire[F[_]](implicit F: Sync[F]): F[Backend[F]] =
    F.delay(JLoggerFactory.getILoggerFactory).flatMap(factory => acquire[F](factory))

  def acquire[F[_]](factory: ILoggerFactory)(implicit F: Sync[F]): F[Backend[F]] =
    F.pure(new Backend[F] {
      def bind(scope: Scope): F[BoundBackend[F]] = F.delay(bindUnsafe(scope))

      def bindUnsafe(scope: Scope): BoundBackend[F] = {
        val logger = factory.getLogger(scope.name)
        new BoundBackend[F] {
          type Repr = AnyRef
          val encoding: ValueEncoding[AnyRef] = Slf4jEncoding.native

          def capture(meta: EventMeta): F[Vault] =
            F.pure(Vault.empty)

          def accepts(meta: EventMeta, attachments: Vault): F[Boolean] =
            F.delay(logger.isEnabledForLevel(severity(meta.level)))

          override protected def prepareOutput(event: CapturedEvent)(implicit S: Sync[F]): F[Delivery[F]] =
            S.delay {
              val message  = renderBody(event.body.encode(encoding))
              val fields   = Vector.newBuilder[KeyValuePair]
              val selected = event.meta.eventName.fold(event.fields)(_ => event.fields.filterNames(_ != "event.name"))
              selected.writeTo(
                encoding,
                new FieldSink[AnyRef] {
                  def put(name: String, value: AnyRef): Unit = {
                    fields += new KeyValuePair(name, value)
                    ()
                  }
                }
              )
              val prepared = fields.result()
              Delivery(emitPrepared(logger, event.meta, message, prepared, event.cause))
            }

          def emit(event: EncodedEvent[AnyRef]): F[Unit] = F.defer {
            val fields = event.fields match {
              case values: java.util.Map[_, _] =>
                values.asScala.iterator.map { case (name, value) =>
                  new KeyValuePair(name.asInstanceOf[String], value)
                }.toVector
              case _ => throw new IllegalArgumentException("encoded SLF4J fields must be an object")
            }
            val selected = if (event.meta.eventName.isDefined) fields.filterNot(_.key == "event.name") else fields
            emitPrepared(logger, event.meta, renderBody(event.body), selected, event.cause)
          }

          private def emitPrepared(
              logger: Logger,
              meta: EventMeta,
              message: String,
              fields: Vector[KeyValuePair],
              cause: Option[Throwable]
          ): F[Unit] = F.blocking {
            // Builders are mutable and invocation-local, including during prepared replay.
            val builder = logger.atLevel(severity(meta.level)).setMessage(message)
            fields.foreach(field => builder.addKeyValue(field.key, field.value))
            meta.eventName.foreach(name => builder.addKeyValue("event.name", name))
            cause.foreach(error => builder.setCause(error))
            builder.log()
          }
        }
      }
    })

  private def renderBody(value: AnyRef): String = if (value == null) "" else value.toString

  private def severity(level: Level): JLevel = level match {
    case Level.Trace               => JLevel.TRACE
    case Level.Debug               => JLevel.DEBUG
    case Level.Info                => JLevel.INFO
    case Level.Warn                => JLevel.WARN
    case Level.Error | Level.Fatal => JLevel.ERROR
  }
}
