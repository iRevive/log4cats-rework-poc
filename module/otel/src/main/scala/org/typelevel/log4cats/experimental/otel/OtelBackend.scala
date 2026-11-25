package org.typelevel.log4cats.experimental.otel

import cats.effect.std.Dispatcher
import cats.effect.{Async, Clock, Resource, Sync}
import cats.syntax.all._
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
import org.typelevel.otel4s.logs.{Logger, LoggerProvider, Severity}
import org.typelevel.otel4s.{AnyValue, AttributeKey}
import org.typelevel.vault.{Key, Vault}
import scala.concurrent.duration.FiniteDuration

object OtelBackend {
  def resource[F[_], Ctx](provider: LoggerProvider[F, Ctx])(implicit
      F: Async[F],
      clock: Clock[F]
  ): Resource[F, Backend[F]] =
    Dispatcher.parallel[F].evalMap { dispatcher =>
      Key.newKey[F, Ctx].map { key =>
        new Backend[F] {
          def bind(scope: Scope): F[BoundBackend[F]] =
            F.defer(provider.get(scope.name)).map(bound)

          def bindUnsafe(scope: Scope): BoundBackend[F] =
            bound(dispatcher.unsafeRunSync(provider.get(scope.name)))

          private def bound(logger: Logger[F, Ctx]): BoundBackend[F] = new BoundBackend[F] {
            type Repr = AnyValue
            val encoding: ValueEncoding[AnyValue] = AnyValueEncoding.anyValue
            def capture(meta: EventMeta): F[Vault] =
              logger.currentContext.map(ctx => Vault.empty.insert(key, ctx))
            def accepts(meta: EventMeta, attachments: Vault): F[Boolean] = attachments.lookup(key) match {
              case Some(ctx) => logger.meta.isEnabled(ctx, Some(severity(meta.level)), meta.eventName)
              case None =>
                F.raiseError(
                  new IllegalStateException("OTel context attachment missing during acceptance")
                )
            }
            override protected def prepareOutput(event: CapturedEvent)(implicit
                S: Sync[F]
            ): F[Delivery[F]] = S.delay {
              val ctx        = requiredContext(event.attachments)
              val body       = event.body.encode(encoding)
              val attributes = Vector.newBuilder[org.typelevel.otel4s.Attribute[_]]
              val selectedFields =
                if (event.cause.isDefined) event.fields.filterNames(name => !reserved(name))
                else event.fields
              selectedFields.writeTo(
                encoding,
                new FieldSink[AnyValue] {
                  def put(name: String, value: AnyValue): Unit =
                    attributes += AttributeKey.anyValue(name)(value)
                }
              )
              event.cause.foreach(error => attributes ++= exceptionAttributes(error))
              val preparedAttributes = attributes.result()
              val meta               = event.meta
              Delivery(F.defer(emitPrepared(meta, body, preparedAttributes, ctx)))
            }

            def emit(event: EncodedEvent[AnyValue]): F[Unit] = F.defer {
              val fields = event.fields match {
                case m: AnyValue.MapValue =>
                  m.value.toVector.collect {
                    case (name, value) if event.cause.isEmpty || !reserved(name) =>
                      AttributeKey.anyValue(name)(value): org.typelevel.otel4s.Attribute[_]
                  }
                case _ => throw new IllegalStateException("encoded OTel fields are not an object")
              }
              val attributes = fields ++ event.cause.toVector.flatMap(exceptionAttributes)
              emitPrepared(event.meta, event.body, attributes, requiredContext(event.attachments))
            }

            private def requiredContext(attachments: Vault): Ctx =
              attachments.lookup(key).getOrElse {
                throw new IllegalStateException("OTel context attachment missing before preparation")
              }

            private def emitPrepared(
                meta: EventMeta,
                body: AnyValue,
                attributes: Vector[org.typelevel.otel4s.Attribute[_]],
                ctx: Ctx
            ): F[Unit] = clock.realTime.flatMap { observedAt =>
              val base = logger.logRecordBuilder
                .withContext(ctx)
                .withTimestamp(
                  FiniteDuration(meta.timestampNanos, java.util.concurrent.TimeUnit.NANOSECONDS)
                )
                .withObservedTimestamp(observedAt)
                .withSeverity(severity(meta.level))
                .withSeverityText(severityText(meta.level))
                .withBody(body)
              val named = meta.eventName.fold(base)(base.withEventName)
              named.addAttributes(attributes).emit
            }
          }
        }
      }
    }

  private val reserved = Set("exception.type", "exception.message", "exception.stacktrace")

  private def severity(level: Level): Severity = level match {
    case Level.Trace => Severity.trace
    case Level.Debug => Severity.debug
    case Level.Info  => Severity.info
    case Level.Warn  => Severity.warn
    case Level.Error => Severity.error
    case Level.Fatal => Severity.fatal
  }

  private def severityText(level: Level): String = level match {
    case Level.Trace => "TRACE"
    case Level.Debug => "DEBUG"
    case Level.Info  => "INFO"
    case Level.Warn  => "WARN"
    case Level.Error => "ERROR"
    case Level.Fatal => "FATAL"
  }

  private def exceptionAttributes(error: Throwable): Seq[org.typelevel.otel4s.Attribute[String]] = {
    val writer = new java.io.StringWriter()
    error.printStackTrace(new java.io.PrintWriter(writer))
    Seq(
      AttributeKey.string("exception.type")(error.getClass.getName),
      AttributeKey.string("exception.message")(Option(error.getMessage).getOrElse("")),
      AttributeKey.string("exception.stacktrace")(writer.toString)
    )
  }
}
