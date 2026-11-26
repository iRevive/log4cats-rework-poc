package consumer

import cats.effect.{IO, Resource}
import cats.syntax.all._
import ch.qos.logback.classic.{Level => NativeLevel, LoggerContext}
import ch.qos.logback.classic.spi.{ILoggingEvent, ThrowableProxy}
import ch.qos.logback.classic.util.LogbackMDCAdapter
import ch.qos.logback.core.read.ListAppender
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.Event
import org.typelevel.log4cats.experimental.encoding.{Encoder, ValueEncoding}
import org.typelevel.log4cats.experimental.slf4j.Slf4j
import scala.collection.JavaConverters._

final class Slf4jFactorySuite extends CatsEffectSuite {
  private def recording: Resource[IO, (LoggerContext, ListAppender[ILoggingEvent])] = Resource.make(IO {
    val context = new LoggerContext()
    context.setMDCAdapter(new LogbackMDCAdapter())
    val appender = new ListAppender[ILoggingEvent]()
    appender.setContext(context)
    appender.start()
    val root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    root.setLevel(NativeLevel.TRACE)
    root.addAppender(appender)
    context.start()
    (context, appender)
  }) { case (context, _) => IO(context.stop()) }

  test("direct factories suspend safe binding, retain handles, and respect native thresholds") {
    recording.use { case (context, appender) =>
      var lookups = 0
      var builds  = 0
      val native = new org.slf4j.ILoggerFactory {
        def getLogger(name: String): org.slf4j.Logger = { lookups += 1; context.getLogger(name) }
      }
      context.getLogger("restricted").setLevel(NativeLevel.ERROR)
      for {
        factory <- Slf4j.factory[IO](native)
        safe = factory.fromName("restricted")
        _       <- IO(assertEquals(lookups, 0))
        logger  <- safe
        _       <- logger.info { builds += 1; "skipped" }
        _       <- logger.error { builds += 1; "first" }
        _       <- logger.error("second")
        _       <- IO(assertEquals(lookups, 1))
        unsafe  <- IO(factory.fromClassUnsafe(classOf[String]))
        _       <- unsafe.fatal("fatal")
        invalid <- factory.fromName("").attempt
      } yield {
        assert(invalid.isLeft)
        assertEquals(lookups, 2)
        assertEquals(builds, 1)
        assertEquals(appender.list.asScala.map(_.getMessage).toVector, Vector("first", "second", "fatal"))
        assertEquals(appender.list.get(2).getLoggerName, classOf[String].getName)
        assertEquals(appender.list.get(2).getLevel, NativeLevel.ERROR)
        assert(context.isStarted)
        intercept[IllegalArgumentException](factory.fromNameUnsafe(""))
      }
    }
  }

  test("direct logging preserves named structured fields, literal messages, and throwable identity") {
    recording.use { case (context, appender) =>
      val cause = new IllegalStateException("original")
      for {
        factory <- Slf4j.factory[IO](context)
        logger  <- factory.fromName("checkout")
        _ <- logger.infoEvent("checkout.completed") {
          Event.body(Map("count" -> 2)).withField("event.name", "shadowed").withField("request.id", 42)
        }
        _ <- logger.error("literal {}", cause)
      } yield {
        val structured = appender.list.get(0)
        assertEquals(structured.getMessage, "{count=2}")
        val fields = structured.getKeyValuePairs.asScala.map(kv => kv.key -> kv.value).toMap
        assertEquals(fields("event.name"), "checkout.completed")
        assertEquals(fields("request.id"), java.lang.Long.valueOf(42))
        val failed = appender.list.get(1)
        assertEquals(failed.getFormattedMessage, "literal {}")
        assert(failed.getThrowableProxy.asInstanceOf[ThrowableProxy].getThrowable eq cause)
      }
    }
  }

  test("direct execution replays lazily and callback or encoding failures emit nothing") {
    recording.use { case (context, appender) =>
      var builds  = 0
      val failure = new RuntimeException("encoding")
      val broken = new Encoder[Int] {
        def encode[V](value: Int)(out: ValueEncoding[V]): V = throw failure
      }
      for {
        factory <- Slf4j.factory[IO](context)
        logger  <- factory.fromName("replay")
        effect = logger.info { builds += 1; "replayed" }
        _        <- IO(assertEquals(builds, 0))
        _        <- effect *> effect
        callback <- logger.infoEvent((throw failure): Event).attempt
        encoded  <- logger.infoEvent(Event.body(1)(broken)).attempt
      } yield {
        assert(callback.swap.exists(_ eq failure))
        assert(encoded.swap.exists(_ eq failure))
        assertEquals(builds, 2)
        assertEquals(appender.list.size(), 2)
      }
    }
  }
}
