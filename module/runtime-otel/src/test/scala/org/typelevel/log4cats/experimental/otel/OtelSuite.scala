package org.typelevel.log4cats.experimental.otel

import cats.effect.{Clock, IO, Resource}
import cats.syntax.all._
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.logs.{
  Logger => JLogger,
  LoggerBuilder,
  LoggerProvider => JLoggerProvider,
  Severity => JSeverity
}
import io.opentelemetry.api.trace.{Span, SpanContext, TraceFlags, TraceState}
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.{Context => JContext}
import java.util.concurrent.ConcurrentLinkedQueue
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.backend.{Backend, EncodedEvent, EventMeta}
import org.typelevel.log4cats.experimental.encoding.{Encoder, ValueEncoding}
import org.typelevel.log4cats.experimental.pipeline.Pipeline
import org.typelevel.log4cats.experimental.value.{Value, ValueBackend}
import org.typelevel.log4cats.experimental.{Event, Field, Fields, Level, Logger, Scope}
import org.typelevel.otel4s.AnyValue
import org.typelevel.otel4s.context.LocalProvider
import org.typelevel.otel4s.oteljava.AnyValueConverters._
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.oteljava.context.{Context => OtelContext}
import org.typelevel.otel4s.oteljava.testkit.logs.LogsTestkit
import scala.concurrent.duration.{FiniteDuration, NANOSECONDS}

final class OtelSuite extends CatsEffectSuite {
  private final case class Domain(name: String, ids: Vector[Long])
  private implicit val domainEncoder: Encoder[Domain] = new Encoder[Domain] {
    def encode[V](value: Domain)(out: ValueEncoding[V]): V = {
      val b = out.obj()
      b.put("name", out.text(value.name))
      val ids = out.array()
      ids.add(out.int64(Long.MinValue)); ids.add(out.int64(Long.MaxValue))
      b.put("ids", ids.result())
      val mixed = out.array(); mixed.add(out.text("text")); mixed.add(out.boolean(false));
      mixed.add(out.empty)
      b.put("mixed", mixed.result())
      b.result()
    }
  }

  test(
    "direct AnyValue interpreter preserves nested primitives, bytes, empty, and boundaries"
  ) {
    val bytes = Array[Byte](4, 5)
    val b     = AnyValueEncoding.anyValue.obj()
    b.put("min", AnyValueEncoding.anyValue.int64(Long.MinValue))
    b.put("max", AnyValueEncoding.anyValue.int64(Long.MaxValue))
    b.put("bytes", AnyValueEncoding.anyValue.bytes(bytes))
    b.put("empty", AnyValueEncoding.anyValue.empty)
    b.put("empty-array", AnyValueEncoding.anyValue.array().result())
    b.put("empty-object", AnyValueEncoding.anyValue.obj().result())
    val encoded = b.result()
    bytes(0) = 0
    val domain     = domainEncoder.encode(Domain("x", Vector.empty))(AnyValueEncoding.anyValue)
    val domainTree = domainEncoder.encode(Domain("x", Vector.empty))(ValueEncoding.value)
    val valueTree = domainTree match {
      case Value.Obj(v) => AnyValue.map(v.map { case (k, value) => k -> valueToAnyValue(value) })
      case _            => fail("domain value should be an object")
    }
    assertEquals(domain, valueTree)
    val copied =
      AnyValueEncoding.anyValue.bytes(Array[Byte](4, 5)).asInstanceOf[AnyValue.ByteArrayValue]
    val original = Array[Byte](4, 5)
    val copiedFromSource =
      AnyValueEncoding.anyValue.bytes(original).asInstanceOf[AnyValue.ByteArrayValue]
    original(0) = 0
    val buffer = copiedFromSource.value
    assertEquals(buffer.get(0), 4.toByte)
    assert(encoded.toString.contains("LongValue(-9223372036854775808)"))
    assert(encoded.toString.contains("ByteArrayValue"))
    assert(domain.isInstanceOf[AnyValue.MapValue])
    assert(
      AnyValueEncoding.anyValue.float64(Double.NaN).asInstanceOf[AnyValue.DoubleValue].value.isNaN
    )
    assertEquals(
      AnyValueEncoding.anyValue
        .float64(Double.PositiveInfinity)
        .asInstanceOf[AnyValue.DoubleValue]
        .value,
      Double.PositiveInfinity
    )
    assertEquals(
      AnyValueEncoding.anyValue
        .float64(Double.NegativeInfinity)
        .asInstanceOf[AnyValue.DoubleValue]
        .value,
      Double.NegativeInfinity
    )
    val builder = AnyValueEncoding.anyValue.obj()
    builder.put("k", AnyValue.string("first")); builder.put("k", AnyValue.string("last"))
    val snapshot = builder.result().asInstanceOf[AnyValue.MapValue]
    assertEquals(snapshot.value.get("k"), Some(AnyValue.string("last")))
    intercept[IllegalStateException](builder.result())
    intercept[IllegalStateException](builder.put("later", AnyValue.empty))
    val arrayBuilder = AnyValueEncoding.anyValue.array(); arrayBuilder.add(AnyValue.string("a"));
    arrayBuilder.add(AnyValue.boolean(false))
    assertEquals(
      arrayBuilder.result().asInstanceOf[AnyValue.SeqValue].value,
      Seq(AnyValue.string("a"), AnyValue.boolean(false))
    )
    intercept[IllegalStateException](arrayBuilder.add(AnyValue.empty))
  }

  test("mixed outputs construct one record and encode before the first emitter") {
    var recordBuilds = 0
    var encoderRuns  = 0
    final class MutableDomain(var name: String)
    val tracked = new Encoder[MutableDomain] {
      def encode[V](value: MutableDomain)(out: ValueEncoding[V]): V = {
        encoderRuns += 1
        val b = out.obj(); b.put("name", out.text(value.name)); b.result()
      }
    }
    LogsTestkit.inMemory[IO]().use { kit =>
      OtelBackend.resource(kit.loggerProvider).use { providerBackend =>
        for {
          sourceValue   <- IO(new MutableDomain("producer"))
          consoleEvents <- cats.effect.Ref.of[IO, Vector[EncodedEvent[Value]]](Vector.empty)
          rejectedEmits <- cats.effect.Ref.of[IO, Int](0)
          console = ValueBackend[IO](
            e => consoleEvents.update(_ :+ e) *> IO { sourceValue.name = "mutated by first emitter" },
            IO.pure(()),
            (_, _) => IO.pure(true)
          )
          rejected = ValueBackend[IO](
            e => rejectedEmits.update(_ + 1),
            IO.pure(()),
            (_, _) => IO.pure(false)
          )
          resource = Pipeline.resource(
            Pipeline.fromBackend[IO](Resource.pure[IO, Backend[IO]](console)) ++
              Pipeline.fromBackend[IO](Resource.pure[IO, Backend[IO]](providerBackend)) ++
              Pipeline.fromBackend[IO](Resource.pure[IO, Backend[IO]](rejected))
          )
          result <- resource.use { factory =>
            for {
              logger        <- factory.fromName("mixed")
              _             <- logger.log(Level.Info, None, w => { recordBuilds += 1; w.body(sourceValue)(tracked) })
              values        <- consoleEvents.get
              rejectedCount <- rejectedEmits.get
              records       <- kit.finishedLogs
            } yield {
              assertEquals(recordBuilds, 1)
              assertEquals(encoderRuns, 2)
              assertEquals(rejectedCount, 0)
              assertEquals(values.head.body, Value.Obj(Map("name" -> Value.Text("producer"))))
              assertEquals(
                records.head.getBodyValue.toScala,
                AnyValue.map(Map("name" -> AnyValue.string("producer")))
              )
            }
          }
        } yield result
      }
    }
  }

  private def valueToAnyValue(value: Value): AnyValue = value match {
    case Value.Empty      => AnyValue.empty
    case Value.Text(v)    => AnyValue.string(v)
    case Value.Bool(v)    => AnyValue.boolean(v)
    case Value.Int64(v)   => AnyValue.long(v)
    case Value.Float64(v) => AnyValue.double(v)
    case Value.Bytes(v)   => AnyValue.bytes(v.toArray)
    case Value.Arr(v)     => AnyValue.seq(v.map(valueToAnyValue))
    case Value.Obj(v)     => AnyValue.map(v.map { case (k, item) => k -> valueToAnyValue(item) })
  }

  test(
    "LoggerProvider exports scope, severity, event name, body, fields and exception"
  ) {
    val tick = new java.util.concurrent.atomic.AtomicInteger(0)
    implicit val controlledClock: Clock[IO] = new Clock[IO] {
      def applicative                   = implicitly[cats.Applicative[IO]]
      def monotonic: IO[FiniteDuration] = IO.pure(FiniteDuration(0L, NANOSECONDS))
      def realTime: IO[FiniteDuration] = IO.delay {
        val millis = if (tick.getAndIncrement() % 2 == 0) 1234L else 2345L
        FiniteDuration(millis, java.util.concurrent.TimeUnit.MILLISECONDS)
      }
    }
    LogsTestkit.inMemory[IO]().use { kit =>
      OtelBackend.resourceWithClock(kit.loggerProvider, controlledClock).use { back =>
        val resource = Pipeline.resource(Pipeline.fromBackend[IO](Resource.pure[IO, Backend[IO]](back)))(
          implicitly[cats.effect.Sync[IO]],
          controlledClock
        )
        resource.use { factory =>
          val cause = new IllegalArgumentException("bad input")
          for {
            logger <- factory.fromName("prototype.scope")
            _ <- logger.logEvent(Level.Error, Some("domain.event"))(
              Event
                .body(Domain("Ada", Vector(1L, 2L)))
                .withFields(Fields(Field("retry", 3), Field("active", true), Field("exception.type", "user-value")))
                .withCause(cause)
            )
            records <- kit.finishedLogs
            _       <- kit.resetLogs
            _       <- logger.infoEvent(Event.message("without cause").withField("exception.type", "application-field"))
            noCause <- kit.finishedLogs
          } yield {
            assertEquals(records.size, 1)
            val record = records.head
            assertEquals(record.getSeverityText, "ERROR")
            assertEquals(record.getEventName, "domain.event")
            assertEquals(record.getInstrumentationScopeInfo.getName, "prototype.scope")
            val expectedBody = AnyValue.map(
              Map(
                "name" -> AnyValue.string("Ada"),
                "ids"  -> AnyValue.seq(Seq(AnyValue.long(Long.MinValue), AnyValue.long(Long.MaxValue))),
                "mixed" -> AnyValue
                  .seq(Seq(AnyValue.string("text"), AnyValue.boolean(false), AnyValue.empty))
              )
            )
            assertEquals(record.getBodyValue.toScala, expectedBody)
            val attrs = record.getAttributes.asMap
            assertEquals(
              attrs.get(io.opentelemetry.api.common.AttributeKey.longKey("retry")),
              java.lang.Long.valueOf(3L)
            )
            assertEquals(
              attrs.get(io.opentelemetry.api.common.AttributeKey.booleanKey("active")),
              java.lang.Boolean.TRUE
            )
            assert(
              attrs.containsKey(io.opentelemetry.api.common.AttributeKey.stringKey("exception.type"))
            )
            assertEquals(
              attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey("exception.type")),
              cause.getClass.getName
            )
            assertEquals(record.getTimestampEpochNanos, 1234000000L)
            assertEquals(record.getObservedTimestampEpochNanos, 2345000000L)
            assertEquals(
              noCause.head.getAttributes.asMap
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("exception.type")),
              "application-field"
            )
            assertEquals(tick.get(), 4)
          }
        }
      }
    }
  }

  test("prepared records retain producer span context and untraced root context") {
    val tracedContext = OtelContext.wrap(
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
    LogsTestkit.inMemory[IO]().use { kit =>
      OtelBackend.resource(kit.loggerProvider).use { backend =>
        val resource = Pipeline.runtimeResource(Pipeline.fromBackend[IO](Resource.pure[IO, Backend[IO]](backend)))
        resource.use { runtime =>
          val competitor = OtelContext.wrap(
            Span
              .wrap(
                SpanContext.create(
                  "fedcba9876543210fedcba9876543210",
                  "fedcba9876543210",
                  TraceFlags.getSampled,
                  TraceState.getDefault
                )
              )
              .storeInContext(JContext.root())
          )
          for {
            tracedDelivery <- kit.contextManager.scope(tracedContext)(
              runtime.prepare(Scope("correlation"), Level.Info, None) { writer =>
                writer.body("traced")
                writer.field("trace_id", "misleading")
                writer.field("span_id", "ordinary")
              }
            )
            _ <- kit.contextManager.scope(competitor)(
              tracedDelivery.deliver.start.flatMap(_.joinWithNever)
            )
            untracedDelivery <- runtime.prepare(Scope("correlation"), Level.Info, None)(_.body("root"))
            _ <- kit.contextManager.scope(competitor)(
              untracedDelivery.deliver.start.flatMap(_.joinWithNever)
            )
            records <- kit.finishedLogs
          } yield {
            assertEquals(records.size, 2)
            assertEquals(records.head.getSpanContext.getTraceId, "0123456789abcdef0123456789abcdef")
            assertEquals(records.head.getSpanContext.getSpanId, "0123456789abcdef")
            assertEquals(
              records.head.getAttributes.asMap
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("trace_id")),
              "misleading"
            )
            assertEquals(
              records.head.getAttributes.asMap
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("span_id")),
              "ordinary"
            )
            assert(!records(1).getSpanContext.isValid)
          }
        }
      }
    }
  }

  test("enabled checks receive captured Java context and severity") {
    val enabledCalls = new ConcurrentLinkedQueue[(JSeverity, JContext)]()
    val noopLogger   = JLoggerProvider.noop().get("spy")
    val spyProvider = new JLoggerProvider {
      def loggerBuilder(name: String): LoggerBuilder = new LoggerBuilder {
        def setSchemaUrl(value: String): LoggerBuilder              = this
        def setInstrumentationVersion(value: String): LoggerBuilder = this
        def build(): JLogger = new JLogger {
          override def isEnabled(severity: JSeverity, context: JContext): Boolean = {
            enabledCalls.add((severity, context))
            true
          }
          def logRecordBuilder = noopLogger.logRecordBuilder()
        }
      }
    }
    val noop = OpenTelemetry.noop()
    val javaOtel = new OpenTelemetry {
      def getTracerProvider                  = noop.getTracerProvider
      override def getMeterProvider          = noop.getMeterProvider
      override def getLogsBridge             = spyProvider
      def getPropagators: ContextPropagators = noop.getPropagators
    }
    val producer = OtelContext.wrap(
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

    for {
      otel <- OtelJava.fromJOpenTelemetry[IO](javaOtel)
      consumer = OtelContext.wrap(
        Span
          .wrap(
            SpanContext.create(
              "fedcba9876543210fedcba9876543210",
              "fedcba9876543210",
              TraceFlags.getSampled,
              TraceState.getDefault
            )
          )
          .storeInContext(JContext.root())
      )
      result <- OtelBackend.resource(otel.loggerProvider).use { factory =>
        for {
          backend <- factory.bind(Scope("enabled-check"))
          meta = EventMeta(Level.Error, Scope("enabled-check"), Some("ignored-by-java-enabled-api"), 0L)
          captured <- otel.contextManager.scope(producer)(backend.capture(meta))
          accepted <- otel.contextManager.scope(consumer)(backend.accepts(meta, captured))
          call     <- IO(Option(enabledCalls.peek()).getOrElse(fail("isEnabled should be called")))
        } yield {
          assert(accepted)
          assertEquals(call._1, JSeverity.ERROR)
          assertEquals(
            Span.fromContext(call._2).getSpanContext.getTraceId,
            "0123456789abcdef0123456789abcdef"
          )
        }
      }
    } yield result
  }
}
