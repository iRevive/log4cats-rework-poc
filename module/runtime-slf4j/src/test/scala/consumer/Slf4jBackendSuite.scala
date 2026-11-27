package consumer

import cats.effect.{IO, Resource}
import cats.syntax.all._
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.classic.{Level => LogbackLevel, LoggerContext}
import munit.CatsEffectSuite
import org.slf4j.Logger
import org.typelevel.log4cats.experimental.backend.{Backend, EventMeta}
import org.typelevel.log4cats.experimental.context.ContextSource
import org.typelevel.log4cats.experimental.encoding.{Encoder, ValueEncoding}
import org.typelevel.log4cats.experimental.pipeline.Pipeline
import org.typelevel.log4cats.experimental.slf4j.{Slf4jBackend, Slf4jEncoding}
import org.typelevel.log4cats.experimental.{Event, Field, Fields, Level, Scope}
import scala.collection.JavaConverters._

final class Slf4jBackendSuite extends CatsEffectSuite {
  private def recording: Resource[IO, (LoggerContext, RecordingAppender)] = Resource.make(IO {
    val context = new LoggerContext()
    context.setMDCAdapter(org.slf4j.MDC.getMDCAdapter)
    val appender = new RecordingAppender()
    appender.setContext(context)
    appender.start()
    val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
    root.setLevel(LogbackLevel.TRACE)
    root.addAppender(appender)
    context.start()
    (context, appender)
  }) { case (context, _) => IO(context.stop()) }

  private def plan(context: LoggerContext): Pipeline[IO] =
    Pipeline.fromBackend[IO](Resource.eval(Slf4jBackend.acquire[IO](context)))

  test("SLF4J receives name/class identity, all severities, literal messages, and the original cause") {
    recording.use { case (context, appender) =>
      val failure = new IllegalArgumentException("bad input")
      Pipeline.resource(plan(context)).use { factory =>
        for {
          named   <- factory.fromName("checkout")
          byClass <- factory.fromClass(classOf[String])
          _ <- named.trace("trace") *> named.debug("debug") *> named.info("literal {} message") *>
            named.warn("warn") *> named.error("failed", failure) *>
            named.fatal("fatal") *> byClass.info("class")
        } yield {
          val events = appender.events.iterator.asScala.toVector
          assertEquals(
            events.map(_.getLevel),
            Vector(
              LogbackLevel.TRACE,
              LogbackLevel.DEBUG,
              LogbackLevel.INFO,
              LogbackLevel.WARN,
              LogbackLevel.ERROR,
              LogbackLevel.ERROR,
              LogbackLevel.INFO
            )
          )
          assertEquals(events.take(6).map(_.getLoggerName), Vector.fill(6)("checkout"))
          assertEquals(events.last.getLoggerName, classOf[String].getName)
          assertEquals(events(2).getMessage, "literal {} message")
          assertEquals(events(2).getFormattedMessage, "literal {} message")
          assert(events(4).getThrowableProxy.asInstanceOf[ThrowableProxy].getThrowable eq failure)
        }
      }
    }
  }

  test("named construction binds native handles once and leaves captures empty") {
    recording.use { case (context, appender) =>
      val lookups = new java.util.concurrent.atomic.AtomicInteger(0)
      val factory = new org.slf4j.ILoggerFactory {
        def getLogger(name: String): org.slf4j.Logger = {
          lookups.incrementAndGet()
          context.getLogger(name)
        }
      }
      Slf4jBackend.acquire[IO](factory).flatMap { backend =>
        val output = Pipeline.fromBackend[IO](Resource.pure[IO, Backend[IO]](backend))
        Pipeline.resource(output).use { factory =>
          val safe = factory.fromName("safe")
          assertEquals(lookups.get(), 0)
          val unsafe = factory.getLoggerFromName("unsafe")
          assertEquals(lookups.get(), 1)
          for {
            logger   <- safe
            _        <- IO(assertEquals(lookups.get(), 2))
            _        <- logger.info("one") *> logger.info("two") *> unsafe.info("three")
            _        <- IO(assertEquals(lookups.get(), 2))
            bound    <- backend.bind(Scope("capture"))
            captured <- bound.capture(EventMeta(Level.Info, Scope("capture"), None, 0L))
          } yield {
            assert(bound.contextFields(captured).isEmpty)
            assertEquals(appender.events.size(), 3)
          }
        }
      }
    }
  }

  test("native per-name thresholds skip callback and encoder evaluation before preparation") {
    recording.use { case (context, appender) =>
      context.getLogger("restricted").setLevel(LogbackLevel.ERROR)
      var builds  = 0
      var encodes = 0
      val encoder = new Encoder[String] {
        def encode[V](value: String)(out: ValueEncoding[V]): V = {
          encodes += 1
          out.text(value)
        }
      }
      Pipeline.resource(plan(context)).use { factory =>
        val restricted = factory.getLoggerFromName("restricted")
        val enabled    = factory.getLoggerFromName("enabled")
        for {
          _ <- restricted.infoEvent { builds += 1; Event.body("skipped")(encoder) }
          _ <- restricted.errorEvent { builds += 1; Event.body("accepted")(encoder) }
          _ <- enabled.debug("independent threshold")
        } yield {
          assertEquals(builds, 1)
          assertEquals(encodes, 1)
          val events = appender.events.iterator.asScala.toVector
          assertEquals(events.map(_.getMessage), Vector("accepted", "independent threshold"))
          assertEquals(events.map(_.getLoggerName), Vector("restricted", "enabled"))
        }
      }
    }
  }

  test("structured fields use native objects, effective context precedence, and event-name metadata") {
    recording.use { case (context, appender) =>
      var overwrittenEncodes = 0
      val discarded = new Encoder[Int] {
        def encode[V](value: Int)(out: ValueEncoding[V]): V = {
          overwrittenEncodes += 1
          out.int64(value.toLong)
        }
      }
      val request = ContextSource(IO.pure(Fields(Field("request.id", "req-42"), Field("attempt", 2))))
      val output  = plan(context).withFields(Fields(Field("service", "checkout"), Field("attempt", 1)))
      Pipeline.resource(output, request).use { factory =>
        val logger = factory.getLoggerFromName("structured")
        logger
          .infoEvent("checkout.completed") {
            Event
              .message("complete")
              .withFields(Fields(Field("attempt", 0)(discarded), Field("attempt", 3)))
              .withField("active", true)
              .withField("details", Map("count" -> Long.MaxValue))
              .withField("items", Vector(Some(1), None))
              .withField("event.name", 0)(discarded)
          }
          .map { _ =>
            val event  = appender.events.peek()
            val fields = event.getKeyValuePairs.asScala.map(kv => kv.key -> kv.value).toMap
            assertEquals(fields("attempt"), java.lang.Long.valueOf(3L))
            assertEquals(fields("active"), java.lang.Boolean.TRUE)
            assertEquals(fields("service"), "checkout")
            assertEquals(fields("request.id"), "req-42")
            assertEquals(fields("event.name"), "checkout.completed")
            val details = fields("details").asInstanceOf[java.util.Map[String, AnyRef]]
            assertEquals(details.get("count"), java.lang.Long.valueOf(Long.MaxValue))
            assertEquals(
              fields("items").asInstanceOf[java.util.List[AnyRef]].asScala.toVector,
              Vector[AnyRef](java.lang.Long.valueOf(1L), null)
            )
            assertEquals(overwrittenEncodes, 0)
            assertEquals(event.getMDCPropertyMap.get("request.id"), "req-42")
            assertEquals(event.getMDCPropertyMap.get("attempt"), "3")
            assertEquals(event.getMDCPropertyMap.get("event.name"), "checkout.completed")
          }
      }
    }
  }

  test("preparation snapshots body and fields; replay and async delivery use fresh builders") {
    recording.use { case (context, appender) =>
      val bytes  = Array[Byte](1, 2, 3)
      val output = plan(context).async(2)
      Pipeline.runtimeResource(output).use { runtime =>
        for {
          prepared <- runtime.prepare(Scope("snapshot"), Level.Info, Some("snapshot.event")) { writer =>
            writer.body(Map("bytes" -> bytes))
            writer.field("bytes", bytes)
          }
          _ <- IO { bytes(0) = 99 }
          _ <- prepared.deliver.start.flatMap(_.joinWithNever)
          _ <- prepared.deliver
          _ <- runtime.flush
        } yield {
          val events = appender.events.iterator.asScala.toVector
          assertEquals(events.size, 2)
          assertEquals(events.map(_.getMessage), Vector.fill(2)("{bytes={$bytes=AQID}}"))
          events.foreach { event =>
            val fields = event.getKeyValuePairs.asScala.toVector
            assertEquals(fields.map(_.key), Vector("bytes", "event.name"))
            val encodedBytes = fields.head.value.asInstanceOf[java.util.Map[String, String]]
            assertEquals(encodedBytes.get("$bytes"), "AQID")
            assertEquals(event.getMDCPropertyMap.get("bytes"), "{$bytes=AQID}")
            assertEquals(event.getMDCPropertyMap.get("event.name"), "snapshot.event")
            intercept[UnsupportedOperationException](encodedBytes.put("new", "value"))
          }
        }
      }
    }
  }

  test("encoding failure prevents both SLF4J output and earlier sibling emission") {
    recording.use { case (context, appender) =>
      val failure = new IllegalArgumentException("encoding failed")
      var encodes = 0
      val broken = new Encoder[String] {
        def encode[V](value: String)(out: ValueEncoding[V]): V = {
          encodes += 1
          if (encodes == 2) throw failure
          out.text(value)
        }
      }
      Pipeline.resource(plan(context) ++ plan(context)).use { factory =>
        factory.getLoggerFromName("broken").infoEvent(Event.body("body")(broken)).attempt.map { result =>
          assertEquals(result, Left(failure): Either[Throwable, Unit])
          assertEquals(encodes, 2)
          assert(appender.events.isEmpty)
        }
      }
    }
  }

  test("native builders seal and return immutable snapshots with exact scalar representations") {
    val encoding = Slf4jEncoding.native
    val obj      = encoding.obj()
    obj.put("value", encoding.int64(Long.MinValue))
    obj.put("value", encoding.int64(Long.MaxValue))
    val snapshot = obj.result().asInstanceOf[java.util.Map[String, AnyRef]]
    assertEquals(snapshot.get("value"), java.lang.Long.valueOf(Long.MaxValue))
    intercept[IllegalStateException](obj.put("later", encoding.empty))
    intercept[IllegalStateException](obj.result())
    intercept[UnsupportedOperationException](snapshot.put("later", "value"))
    val array = encoding.array()
    array.add(encoding.boolean(false))
    array.add(encoding.float64(Double.PositiveInfinity))
    array.add(encoding.float64(Double.NaN))
    array.add(encoding.empty)
    val values = array.result().asInstanceOf[java.util.List[AnyRef]]
    assertEquals(values.get(0), java.lang.Boolean.FALSE)
    assertEquals(values.get(1), java.lang.Double.valueOf(Double.PositiveInfinity))
    assert(values.get(2).asInstanceOf[java.lang.Double].isNaN)
    assertEquals(values.get(3), null)
    intercept[IllegalStateException](array.add("later"))
    intercept[UnsupportedOperationException](values.add("later"))
  }
}
