package org.typelevel.log4cats.experimental.otel

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._
import io.opentelemetry.api.common.{AttributeKey => JAttributeKey}
import io.opentelemetry.api.trace.{Span, SpanContext, TraceFlags, TraceState}
import io.opentelemetry.context.{Context => JContext}
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.backend.{Backend, EncodedEvent}
import org.typelevel.log4cats.experimental.context.ContextSource
import org.typelevel.log4cats.experimental.pipeline.Pipeline
import org.typelevel.log4cats.experimental.value.{Value, ValueBackend}
import org.typelevel.log4cats.experimental.{Event, Field, Fields, Level, Scope}
import org.typelevel.otel4s.AnyValue
import org.typelevel.otel4s.oteljava.AnyValueConverters._
import org.typelevel.otel4s.oteljava.context.{Context => OtelContext}
import org.typelevel.otel4s.oteljava.testkit.logs.LogsTestkit

final class OtelPipelineSuite extends CatsEffectSuite {
  private val producer = OtelContext.wrap(
    Span
      .wrap(
        SpanContext.create(
          "0123456789abcdef0123456789abcdef",
          "0123456789abcdef",
          TraceFlags.getSampled,
          TraceState.getDefault
        )
      )
      .storeInContext(JContext.root())
  )

  test("mixed async pipeline captures producer context once and respects per-branch levels") {
    LogsTestkit.inMemory[IO]().use { kit =>
      for {
        console  <- Ref.of[IO, Vector[EncodedEvent[Value]]](Vector.empty)
        captures <- Ref.of[IO, Int](0)
        consoleBackend = ValueBackend[IO](
          e => console.update(_ :+ e),
          IO.pure(()),
          (_, _) => IO.pure(true)
        )
        consolePlan = Pipeline
          .fromBackend[IO](Resource.pure[IO, Backend[IO]](consoleBackend))
          .atLeast(Level.Info)
        otelPlan = Pipeline
          .fromBackend[IO](
            OtelBackend.resource(
              kit.loggerProvider,
              (ctx: OtelContext) => {
                val span = Span.fromContext(ctx.underlying).getSpanContext
                Fields(Field("trace_id", span.getTraceId), Field("span_id", span.getSpanId))
              }
            )
          )
          .atLeast(Level.Debug)
        plan = (consolePlan ++ otelPlan)
          .withContextSource(
            ContextSource(
              captures.update(_ + 1).as(Fields(Field("request.id", "request-1")))
            )
          )
          .async(4)
        _ <- Pipeline
          .runtimeResource(plan)
          .use { runtime =>
            runtime.fromName("pipeline.scope").flatMap { logger =>
              kit.contextManager.scope(producer) {
                logger.debugEvent(Event.body(Map("count" -> 1))) *>
                  logger.infoEvent(Event.body(Map("count" -> 2)))
              } *> runtime.flush
            }
          }
        records <- kit.finishedLogs
        local   <- console.get
        count   <- captures.get
      } yield {
        assertEquals(records.size, 2)
        assertEquals(local.size, 1)
        assertEquals(count, 2)
        assertEquals(
          local.head.fields,
          Value.Obj(
            Map(
              "request.id" -> Value.Text("request-1"),
              "trace_id"   -> Value.Text("0123456789abcdef0123456789abcdef"),
              "span_id"    -> Value.Text("0123456789abcdef")
            )
          ): Value
        )
        assertEquals(
          records.map(_.getSpanContext.getTraceId).toVector,
          Vector.fill(2)("0123456789abcdef0123456789abcdef")
        )
        assertEquals(
          records.head.getBodyValue.toScala,
          AnyValue.map(Map("count" -> AnyValue.long(1)))
        )
        assertEquals(
          records.head.getAttributes.get(JAttributeKey.stringKey("request.id")),
          "request-1"
        )
      }
    }
  }

  test("a shared OTel backend preserves each logger name") {
    LogsTestkit.inMemory[IO]().use { kit =>
      val plan = Pipeline.fromBackend[IO](OtelBackend.resource(kit.loggerProvider)).async(2)
      Pipeline.runtimeResource(plan).use { runtime =>
        for {
          first   <- runtime.fromName("first.component")
          second  <- IO.blocking(runtime.fromNameUnsafe("second.component"))
          _       <- kit.contextManager.scope(producer)(first.info("one") *> second.info("two")) *> runtime.flush
          records <- kit.finishedLogs
        } yield {
          assertEquals(
            records.map(_.getInstrumentationScopeInfo.getName).toVector,
            Vector("first.component", "second.component")
          )
          assertEquals(
            records.map(_.getSpanContext.getTraceId).toVector,
            Vector.fill(2)("0123456789abcdef0123456789abcdef")
          )
        }
      }
    }
  }

  test("native preparation snapshots exception presentation before delivery replay") {
    LogsTestkit.inMemory[IO]().use { kit =>
      val error         = new RuntimeException("failure")
      val originalFrame = new StackTraceElement("Original", "run", "Original.scala", 10)
      error.setStackTrace(Array(originalFrame))
      val plan =
        Pipeline.fromBackend[IO](OtelBackend.resource(kit.loggerProvider))
      Pipeline.runtimeResource(plan).use { runtime =>
        for {
          delivery <- runtime.prepare(Scope("exception.snapshot"), Level.Error, None) { writer =>
            writer.body("failed")
            writer.cause(error)
            writer.field("exception.type", "user-value")
          }
          _ <- IO(
            error.setStackTrace(Array(new StackTraceElement("Changed", "run", "Changed.scala", 20)))
          )
          _       <- delivery.deliver *> delivery.deliver
          records <- kit.finishedLogs
        } yield {
          assertEquals(records.size, 2)
          records.foreach { record =>
            val stack = record.getAttributes.get(JAttributeKey.stringKey("exception.stacktrace"))
            assert(stack.contains("Original.run"))
            assert(!stack.contains("Changed.run"))
            assertEquals(
              record.getAttributes.get(JAttributeKey.stringKey("exception.type")),
              "java.lang.RuntimeException"
            )
          }
        }
      }
    }
  }

  test("exception preparation failure happens before a sibling emits") {
    LogsTestkit.inMemory[IO]().use { kit =>
      val failure = new RuntimeException("stack rendering failed")
      val cause = new RuntimeException("broken throwable") {
        override def printStackTrace(writer: java.io.PrintWriter): Unit = throw failure
      }
      for {
        emissions <- Ref.of[IO, Int](0)
        sink = ValueBackend[IO](
          _ => emissions.update(_ + 1),
          IO.pure(()),
          (_, _) => IO.pure(true)
        )
        local = Pipeline.fromBackend[IO](Resource.pure[IO, Backend[IO]](sink))
        otel = Pipeline
          .fromBackend[IO](OtelBackend.resource(kit.loggerProvider))
        result <- Pipeline
          .runtimeResource(local ++ otel)
          .use { runtime =>
            runtime.fromName("prepare-failure").flatMap(_.errorEvent(Event.exception(cause)))
          }
          .attempt
        count   <- emissions.get
        records <- kit.finishedLogs
      } yield {
        assertEquals(result, Left(failure): Either[Throwable, Unit])
        assertEquals(count, 0)
        assert(records.isEmpty)
      }
    }
  }
}
