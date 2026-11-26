package consumer

import cats.effect.{IO, IOLocal, Ref, Resource, SyncIO}
import cats.syntax.all._
import ch.qos.logback.classic.{Level => NativeLevel, LoggerContext}
import ch.qos.logback.classic.spi.{ILoggingEvent, ThrowableProxy}
import ch.qos.logback.core.read.ListAppender
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.{Event, Field, Fields}
import org.typelevel.log4cats.experimental.encoding.{Encoder, ValueEncoding}
import org.typelevel.log4cats.experimental.slf4j.Slf4j
import org.typelevel.log4cats.experimental.context.ContextSource
import scala.collection.JavaConverters._

final class Slf4jFactorySuite extends CatsEffectSuite {
  private def recording: Resource[IO, (LoggerContext, ListAppender[ILoggingEvent])] = Resource.make(IO {
    val context = new LoggerContext()
    context.setMDCAdapter(org.slf4j.MDC.getMDCAdapter)
    val appender = new ListAppender[ILoggingEvent]() {
      override protected def append(event: ILoggingEvent): Unit = {
        event.prepareForDeferredProcessing()
        super.append(event)
      }
    }
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
  test("context is captured once per execution in the calling fiber and merged with call fields") {
    recording.use { case (native, appender) =>
      for {
        local <- IOLocal(Fields(Field("request.id", "parent"), Field("attempt", 1)))
        reads <- Ref.of[IO, Int](0)
        source = ContextSource(reads.update(_ + 1) *> local.get) ++
          ContextSource(IO.pure(Fields(Field("service", "checkout"))))
        factory <- Slf4j.factory[IO](native, source)
        logger  <- factory.fromName("contextual")
        effect = logger.infoEvent("checkout.completed") {
          Event.message("complete").withField("attempt", 2)
        }
        _ <- reads.get.flatMap(n => IO(assertEquals(n, 0)))
        _ <- (
          local.set(Fields(Field("request.id", "left"), Field("attempt", 1))) *> effect,
          local.set(Fields(Field("request.id", "right"), Field("attempt", 1))) *> effect
        ).parTupled
        other = factory.fromNameUnsafe("other")
        _     <- other.info("parent")
        count <- reads.get
      } yield {
        assertEquals(count, 3)
        val events = appender.list.asScala.toVector
        assertEquals(events.take(2).map(_.getMDCPropertyMap.get("request.id")).toSet, Set("left", "right"))
        events.take(2).foreach { event =>
          assertEquals(event.getMDCPropertyMap.get("attempt"), "2")
          assertEquals(event.getMDCPropertyMap.get("service"), "checkout")
          assertEquals(event.getMDCPropertyMap.get("event.name"), "checkout.completed")
          val fields = event.getKeyValuePairs.asScala.map(kv => kv.key -> kv.value).toMap
          assertEquals(fields("attempt"), java.lang.Long.valueOf(2))
          assertEquals(fields("request.id"), event.getMDCPropertyMap.get("request.id"))
        }
        assertEquals(events.last.getMDCPropertyMap.get("request.id"), "parent")
      }
    }
  }

  test("context and event name are installed for native enablement checks") {
    recording.use { case (native, appender) =>
      native.addTurboFilter(new ch.qos.logback.classic.turbo.TurboFilter {
        override def decide(
            marker: org.slf4j.Marker,
            logger: ch.qos.logback.classic.Logger,
            level: NativeLevel,
            format: String,
            params: Array[AnyRef],
            error: Throwable
        ): ch.qos.logback.core.spi.FilterReply =
          if (
            org.slf4j.MDC.get("request.id") == "req-42" &&
            org.slf4j.MDC.get("event.name") == "checkout.completed"
          ) ch.qos.logback.core.spi.FilterReply.ACCEPT
          else ch.qos.logback.core.spi.FilterReply.DENY
      })
      var builds = 0
      for {
        factory <- Slf4j.factory[IO](native, IO.pure(Fields(Field("request.id", "req-42"))))
        logger  <- factory.fromName("filtered")
        _       <- logger.infoEvent("checkout.completed") { builds += 1; Event.message("accepted") }
        _       <- logger.info { builds += 1; "rejected" }
      } yield {
        assertEquals(builds, 1)
        assertEquals(appender.list.size(), 1)
        assertEquals(appender.list.get(0).getMessage, "accepted")
      }
    }
  }

  test("MDC excludes unrelated thread state and restores it after success and native failure") {
    recording.use { case (native, appender) =>
      IO {
        val previous = org.slf4j.MDC.getCopyOfContextMap
        try {
          org.slf4j.MDC.clear()
          org.slf4j.MDC.put("host", "retained")
          val context = SyncIO.pure(Fields(Field("request.id", "req-42")))
          val factory = Slf4j.factory[SyncIO](native, context).unsafeRunSync()
          val logger  = factory.fromNameUnsafe("restoration")
          logger.info("success").unsafeRunSync()
          assertEquals(org.slf4j.MDC.getCopyOfContextMap.asScala.toMap, Map("host" -> "retained"))
          assertEquals(appender.list.get(0).getMDCPropertyMap.asScala.toMap, Map("request.id" -> "req-42"))
          val failure  = new RuntimeException("native failure")
          val delegate = native.getLogger("failure")
          val throwing = java.lang.reflect.Proxy
            .newProxyInstance(
              classOf[org.slf4j.Logger].getClassLoader,
              Array(classOf[org.slf4j.Logger]),
              new java.lang.reflect.InvocationHandler {
                def invoke(proxy: AnyRef, method: java.lang.reflect.Method, args: Array[AnyRef]): AnyRef = {
                  assertEquals(org.slf4j.MDC.get("request.id"), "req-42")
                  assertEquals(org.slf4j.MDC.get("host"), null)
                  if (method.getName == "atLevel") throw failure
                  method.invoke(delegate, Option(args).getOrElse(Array.empty[AnyRef]): _*)
                }
              }
            )
            .asInstanceOf[org.slf4j.Logger]
          val throwingFactory = new org.slf4j.ILoggerFactory {
            def getLogger(name: String): org.slf4j.Logger = throwing
          }
          val failed = Slf4j
            .factory[SyncIO](throwingFactory, context)
            .unsafeRunSync()
            .fromNameUnsafe("failure")
            .info("failure")
            .attempt
            .unsafeRunSync()
          assert(failed.swap.exists(_ eq failure))
          assertEquals(org.slf4j.MDC.getCopyOfContextMap.asScala.toMap, Map("host" -> "retained"))
        } finally
          if (previous == null) org.slf4j.MDC.clear() else org.slf4j.MDC.setContextMap(previous)
      }
    }
  }

  test("context failure is suspended and prevents callback execution and emission") {
    recording.use { case (native, appender) =>
      val failure = new RuntimeException("context failure")
      var builds  = 0
      for {
        factory <- Slf4j.factory[IO](native, IO.raiseError[Fields](failure))
        logger  <- factory.fromName("failed-context")
        effect = logger.info { builds += 1; "unreachable" }
        result <- effect.attempt
      } yield {
        assert(result.swap.exists(_ eq failure))
        assertEquals(builds, 0)
        assertEquals(appender.list.size(), 0)
      }
    }
  }

}
