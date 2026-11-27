package org.typelevel.log4cats.experimental.otel

import cats.Applicative
import cats.effect.{IO, IOLocal, Ref}
import cats.mtl.LiftValue
import cats.syntax.all._
import org.typelevel.log4cats.experimental.{Field, Fields}
import org.typelevel.log4cats.experimental.context.ContextSource
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.testkit.logs.LogsTestkit

final class OtelFactorySuite extends CatsEffectSuite {
  test("safe binding stays in the caller effect and each native logger is resolved only once") {
    LogsTestkit.inMemory[IO]().use { kit =>
      for {
        local   <- IOLocal("startup")
        lookups <- Ref.of[IO, Vector[String]](Vector.empty)
        lift = new LiftValue[IO, IO] {
          def applicativeF: Applicative[IO] = Applicative[IO]
          def applicativeG: Applicative[IO] = Applicative[IO]
          def apply[A](fa: IO[A]): IO[A]    = IO.cede *> local.get.flatMap(value => lookups.update(_ :+ value)) *> fa
        }
        provider = kit.loggerProvider.liftTo[IO](implicitly[cats.Monad[IO]], lift)
        _ <- Otel.resource(provider).use { factory =>
          val safe = factory.fromName("safe.component")
          for {
            before       <- lookups.get
            _            <- IO(assertEquals(before, Vector.empty[String]))
            _            <- local.set("caller")
            safeLogger   <- safe
            afterSafe    <- lookups.get
            _            <- IO(assertEquals(afterSafe, Vector("caller")))
            unsafeLogger <- IO.blocking(factory.getLoggerFromName("unsafe.component"))
            afterUnsafe  <- lookups.get
            _            <- IO(assertEquals(afterUnsafe.size, 2))
            _            <- safeLogger.info("one") *> safeLogger.info("two") *> unsafeLogger.info("three")
            records      <- kit.finishedLogs
          } yield assertEquals(
            records.map(_.getInstrumentationScopeInfo.getName).toVector,
            Vector("safe.component", "safe.component", "unsafe.component")
          )
        }
      } yield ()
    }
  }

  test("factory Resource release shuts down unsafe lookup without closing the provider") {
    LogsTestkit.inMemory[IO]().use { kit =>
      for {
        allocated <- Otel.resource(kit.loggerProvider).allocated
        (factory, close) = allocated
        _       <- IO.blocking(factory.getLoggerFromName("before-close")).guarantee(close)
        failed  <- IO.blocking(factory.getLoggerFromName("after-close")).attempt
        _       <- Otel.resource(kit.loggerProvider).use(_.fromName("still-open").flatMap(_.info("open")))
        records <- kit.finishedLogs
      } yield {
        assert(failed.swap.exists(_.isInstanceOf[IllegalStateException]))
        assertEquals(records.size, 1)
      }
    }
  }
  test("direct records capture execution context and encode structured events and exceptions") {
    val producer = org.typelevel.otel4s.oteljava.context.Context.wrap(
      io.opentelemetry.api.trace.Span
        .wrap(
          io.opentelemetry.api.trace.SpanContext.create(
            "0123456789abcdef0123456789abcdef",
            "0123456789abcdef",
            io.opentelemetry.api.trace.TraceFlags.getSampled,
            io.opentelemetry.api.trace.TraceState.getDefault
          )
        )
        .storeInContext(io.opentelemetry.context.Context.root())
    )
    val cause        = new IllegalStateException("failed")
    var builds       = 0
    var contextReads = 0
    val source = ContextSource.fromFields(IO {
      contextReads += 1
      Fields(Field("service", "checkout"), Field("request.id", -1))
    })
    LogsTestkit.inMemory[IO]().use { kit =>
      Otel.resource(kit.loggerProvider, source).use { factory =>
        for {
          logger <- factory.fromName("checkout")
          effect = logger.infoEvent("checkout.completed") {
            builds += 1
            org.typelevel.log4cats.experimental.Event
              .body(Map("count" -> 2))
              .withField("request.id", 42)
              .withField("exception.message", "shadowed")
              .withCause(cause)
          }
          _       <- IO(assertEquals(builds, 0))
          _       <- kit.contextManager.scope(producer)(effect)
          _       <- effect
          failed  <- logger.infoEvent((throw cause): org.typelevel.log4cats.experimental.Event).attempt
          records <- kit.finishedLogs
        } yield {
          import io.opentelemetry.api.common.AttributeKey
          assertEquals(builds, 2)
          assertEquals(contextReads, 3)
          assert(failed.swap.exists(_ eq cause))
          assertEquals(records.size, 2)
          assertEquals(records.head.getSpanContext.getTraceId, "0123456789abcdef0123456789abcdef")
          assert(!records.last.getSpanContext.isValid)
          assertEquals(records.head.getEventName, "checkout.completed")
          assertEquals(records.head.getAttributes.get(AttributeKey.stringKey("service")), "checkout")
          assertEquals(records.head.getAttributes.get(AttributeKey.longKey("request.id")), java.lang.Long.valueOf(42))
          assertEquals(records.head.getAttributes.get(AttributeKey.stringKey("exception.message")), "failed")
          assert(records.head.getTimestampEpochNanos > 0L)
          assert(records.head.getObservedTimestampEpochNanos >= records.head.getTimestampEpochNanos)
        }
      }
    }
  }

  test("direct OTel maps MDC strings only at the backend, with flat and prefixed collisions") {
    import org.typelevel.log4cats.experimental.{Event, Mdc}
    import org.typelevel.log4cats.experimental.context.LogContext
    import io.opentelemetry.api.common.AttributeKey
    LogsTestkit.inMemory[IO]().use { kit =>
      val source = ContextSource.const[IO](LogContext(Mdc("id" -> "ambient", "only" -> "42")))
      val event = Event
        .message("channels")
        .withMdc("id", "call")
        .withMdc("exception.message", "fake")
        .withField("id", 42)
        .withCause(new IllegalStateException("real"))
      for {
        _ <- Otel.resource(kit.loggerProvider, source).use(_.fromName("flat").flatMap(_.infoEvent(event)))
        _ <- Otel
          .resource(kit.loggerProvider, source, MdcMapping.Prefixed("mdc."))
          .use(_.fromName("prefixed").flatMap(_.infoEvent(event)))
        records <- kit.finishedLogs
      } yield {
        val flat     = records.head.getAttributes
        val prefixed = records.last.getAttributes
        assertEquals(flat.get(AttributeKey.longKey("id")), java.lang.Long.valueOf(42))
        assertEquals(flat.get(AttributeKey.stringKey("only")), "42")
        assertEquals(flat.get(AttributeKey.stringKey("exception.message")), "real")
        assertEquals(prefixed.get(AttributeKey.longKey("id")), java.lang.Long.valueOf(42))
        assertEquals(prefixed.get(AttributeKey.stringKey("mdc.id")), "call")
        assertEquals(prefixed.get(AttributeKey.stringKey("mdc.only")), "42")
        assertEquals(prefixed.get(AttributeKey.stringKey("mdc.exception.message")), "fake")
      }
    }
  }

}
