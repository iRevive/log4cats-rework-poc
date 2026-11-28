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
import org.typelevel.log4cats.experimental.context.{ContextSource, LogContext}
import org.typelevel.log4cats.experimental.Mdc
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
      val factory = Slf4j.factory[IO](Slf4j.Config(native = native))
      val safe    = factory.fromName("restricted")
      for {
        _       <- IO(assertEquals(lookups, 0))
        logger  <- safe
        _       <- logger.info { builds += 1; "skipped" }
        _       <- logger.error { builds += 1; "first" }
        _       <- logger.error("second")
        _       <- IO(assertEquals(lookups, 1))
        unsafe  <- IO(factory.getLoggerFromClass(classOf[String]))
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
        intercept[IllegalArgumentException](factory.getLoggerFromName(""))
      }
    }
  }

  test("direct logging preserves named structured fields, literal messages, and throwable identity") {
    recording.use { case (context, appender) =>
      val cause   = new IllegalStateException("original")
      val factory = Slf4j.factory[IO](Slf4j.Config(native = context))
      for {
        logger <- factory.fromName("checkout")
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
      val factory = Slf4j.factory[IO](Slf4j.Config(native = context))
      for {
        logger <- factory.fromName("replay")
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
        local <- IOLocal("parent")
        reads <- Ref.of[IO, Int](0)
        source = ContextSource(
          reads.update(_ + 1) *> local.get.map(id =>
            LogContext(
              mdc = Mdc("request.id" -> id, "attempt" -> "context"),
              fields = Fields(Field("request.id", id), Field("attempt", 1))
            )
          )
        ) ++
          ContextSource.const[IO](Fields(Field("service", "checkout")))
        factory = Slf4j.factory[IO](source, Slf4j.Config(native = native))
        logger <- factory.fromName("contextual")
        effect = logger.infoEvent("checkout.completed") {
          Event.message("complete").withField("attempt", 2)
        }
        _ <- reads.get.flatMap(n => IO(assertEquals(n, 0)))
        _ <- (
          local.set("left") *> effect,
          local.set("right") *> effect
        ).parTupled
        other = factory.getLoggerFromName("other")
        _     <- other.info("parent")
        count <- reads.get
      } yield {
        assertEquals(count, 3)
        val events = appender.list.asScala.toVector
        assertEquals(events.take(2).map(_.getMDCPropertyMap.get("request.id")).toSet, Set("left", "right"))
        events.take(2).foreach { event =>
          assertEquals(event.getMDCPropertyMap.get("attempt"), "context")
          assertEquals(event.getMDCPropertyMap.get("service"), null)
          assertEquals(event.getMDCPropertyMap.get("event.name"), null)
          val fields = event.getKeyValuePairs.asScala.map(kv => kv.key -> kv.value).toMap
          assertEquals(fields("attempt"), java.lang.Long.valueOf(2))
          assertEquals(fields("request.id"), event.getMDCPropertyMap.get("request.id"))
        }
        assertEquals(events.last.getMDCPropertyMap.get("request.id"), "parent")
      }
    }
  }

  test("only explicit MDC is installed for native enablement checks") {
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
            org.slf4j.MDC.get("event.name") == null
          ) ch.qos.logback.core.spi.FilterReply.ACCEPT
          else ch.qos.logback.core.spi.FilterReply.DENY
      })
      var builds = 0
      val factory = Slf4j.factory[IO](
        ContextSource.const[IO](LogContext(mdc = Mdc("request.id" -> "req-42"))),
        Slf4j.Config(native = native)
      )
      for {
        logger <- factory.fromName("filtered")
        _      <- logger.infoEvent("checkout.completed") { builds += 1; Event.message("accepted") }
        _ <- Slf4j.factory[IO](Slf4j.Config(native = native)).getLoggerFromName("filtered").info {
          builds += 1; "rejected"
        }
      } yield {
        assertEquals(builds, 1)
        assertEquals(appender.list.size(), 1)
        assertEquals(appender.list.get(0).getMessage, "accepted")
      }
    }
  }

  List(Slf4j.MdcPolicy.Isolate, Slf4j.MdcPolicy.Overlay).foreach { policy =>
    test(s"MDC $policy handles collisions and restores thread state after success and native failure") {
      recording.use { case (native, appender) =>
        IO {
          val previous = org.slf4j.MDC.getCopyOfContextMap
          try {
            org.slf4j.MDC.clear()
            org.slf4j.MDC.put("host", "retained")
            org.slf4j.MDC.put("request.id", "thread")
            val before = Map("host" -> "retained", "request.id" -> "thread")
            val expected = policy match {
              case Slf4j.MdcPolicy.Isolate => Map("request.id" -> "req-42")
              case Slf4j.MdcPolicy.Overlay => before.updated("request.id", "req-42")
            }
            val context = ContextSource.const[SyncIO](LogContext(mdc = Mdc("request.id" -> "req-42")))
            val config  = Slf4j.Config(native = native, mdcPolicy = policy)
            val logger  = Slf4j.factory[SyncIO](context, config).getLoggerFromName("restoration")
            logger.info("success").unsafeRunSync()
            assertEquals(org.slf4j.MDC.getCopyOfContextMap.asScala.toMap, before)
            assertEquals(appender.list.get(0).getMDCPropertyMap.asScala.toMap, expected)
            // Empty logical MDC still respects isolation versus overlay.
            Slf4j.factory[SyncIO](config).getLoggerFromName("empty").info("empty").unsafeRunSync()
            assertEquals(
              appender.list.get(1).getMDCPropertyMap.asScala.toMap,
              if (policy == Slf4j.MdcPolicy.Overlay) before else Map.empty[String, String]
            )
            assertEquals(org.slf4j.MDC.getCopyOfContextMap.asScala.toMap, before)

            List("isEnabledForLevel", "atLevel").foreach { failAt =>
              val failure  = new RuntimeException(s"native failure at $failAt")
              val delegate = native.getLogger("failure")
              val throwing = java.lang.reflect.Proxy
                .newProxyInstance(
                  classOf[org.slf4j.Logger].getClassLoader,
                  Array(classOf[org.slf4j.Logger]),
                  new java.lang.reflect.InvocationHandler {
                    def invoke(proxy: AnyRef, method: java.lang.reflect.Method, args: Array[AnyRef]): AnyRef = {
                      assertEquals(org.slf4j.MDC.getCopyOfContextMap.asScala.toMap, expected)
                      if (method.getName == failAt) {
                        org.slf4j.MDC.put("native.extra", "temporary")
                        throw failure
                      }
                      method.invoke(delegate, Option(args).getOrElse(Array.empty[AnyRef]): _*)
                    }
                  }
                )
                .asInstanceOf[org.slf4j.Logger]
              val throwingFactory = new org.slf4j.ILoggerFactory {
                def getLogger(name: String): org.slf4j.Logger = throwing
              }
              val failed = Slf4j
                .factory[SyncIO](context, config.copy(native = throwingFactory))
                .getLoggerFromName("failure")
                .info("failure")
                .attempt
                .unsafeRunSync()
              assert(failed.swap.exists(_ eq failure))
              assertEquals(org.slf4j.MDC.getCopyOfContextMap.asScala.toMap, before)
            }
            org.slf4j.MDC.clear()
            logger.info("initially empty").unsafeRunSync()
            assert(Option(org.slf4j.MDC.getCopyOfContextMap).forall(_.isEmpty))
          } finally
            if (previous == null) org.slf4j.MDC.clear() else org.slf4j.MDC.setContextMap(previous)
        }
      }
    }
  }

  test("overlay exposes thread MDC to MDC-aware filters and restores it on rejection") {
    recording.use { case (native, appender) =>
      IO {
        val previous = org.slf4j.MDC.getCopyOfContextMap
        try {
          org.slf4j.MDC.clear()
          org.slf4j.MDC.put("legacy", "accepted")
          native.addTurboFilter(new ch.qos.logback.classic.turbo.TurboFilter {
            override def decide(
                marker: org.slf4j.Marker,
                logger: ch.qos.logback.classic.Logger,
                level: NativeLevel,
                format: String,
                params: Array[AnyRef],
                error: Throwable
            ): ch.qos.logback.core.spi.FilterReply =
              if (org.slf4j.MDC.get("legacy") == "accepted") ch.qos.logback.core.spi.FilterReply.ACCEPT
              else ch.qos.logback.core.spi.FilterReply.DENY
          })
          val source  = ContextSource.const[SyncIO](LogContext(mdc = Mdc("request.id" -> "logical")))
          val config  = Slf4j.Config(native = native, mdcPolicy = Slf4j.MdcPolicy.Overlay)
          val overlay = Slf4j.factory[SyncIO](source, config).getLoggerFromName("overlay")
          val isolated = Slf4j
            .factory[SyncIO](source, config.copy(mdcPolicy = Slf4j.MdcPolicy.Isolate))
            .getLoggerFromName("isolated")
          var builds = 0
          isolated.info { builds += 1; "rejected" }.unsafeRunSync()
          assertEquals(builds, 0)
          assertEquals(org.slf4j.MDC.getCopyOfContextMap.asScala.toMap, Map("legacy" -> "accepted"))
          val rejectedSource = ContextSource.const[SyncIO](LogContext(mdc = Mdc("legacy" -> "denied")))
          Slf4j
            .factory[SyncIO](rejectedSource, config)
            .getLoggerFromName("overlay-rejected")
            .info { builds += 1; "rejected" }
            .unsafeRunSync()
          assertEquals(builds, 0)
          assertEquals(org.slf4j.MDC.getCopyOfContextMap.asScala.toMap, Map("legacy" -> "accepted"))
          overlay.info("accepted").unsafeRunSync()
          assertEquals(appender.list.size(), 1)
          assertEquals(
            appender.list.get(0).getMDCPropertyMap.asScala.toMap,
            Map("legacy" -> "accepted", "request.id" -> "logical")
          )
          assertEquals(org.slf4j.MDC.getCopyOfContextMap.asScala.toMap, Map("legacy" -> "accepted"))
        } finally
          if (previous == null) org.slf4j.MDC.clear() else org.slf4j.MDC.setContextMap(previous)
      }
    }
  }

  test("context failure is suspended and prevents callback execution and emission") {
    recording.use { case (native, appender) =>
      val failure = new RuntimeException("context failure")
      var builds  = 0
      val factory =
        Slf4j.factory[IO](ContextSource.fromFields(IO.raiseError[Fields](failure)), Slf4j.Config(native = native))
      for {
        logger <- factory.fromName("failed-context")
        effect = logger.info { builds += 1; "unreachable" }
        result <- effect.attempt
      } yield {
        assert(result.swap.exists(_ eq failure))
        assertEquals(builds, 0)
        assertEquals(appender.list.size(), 0)
      }
    }
  }

  test("Native checks skip context reads and encoding for disabled direct calls") {
    recording.use { case (native, appender) =>
      var encodes = 0
      var builds  = 0
      val encoder = new Encoder[Int] {
        def encode[V](value: Int)(out: ValueEncoding[V]): V = { encodes += 1; out.int64(value.toLong) }
      }
      for {
        reads <- Ref.of[IO, Int](0)
        _     <- IO(native.getLogger("native-check").setLevel(NativeLevel.ERROR))
        source = ContextSource.fromFields(reads.update(_ + 1).as(Fields(Field("value", 42)(encoder))))
        logger = Slf4j
          .factory[IO](source, Slf4j.Config(native = native, enablement = Slf4j.Enablement.Native))
          .getLoggerFromName("native-check")
        before        <- reads.get
        _             <- logger.debug { builds += 1; "disabled" }.replicateA_(1000)
        afterDisabled <- reads.get
        _ <- IO {
          assertEquals(encodes, 0)
          assertEquals(builds, 0)
          native.getLogger("native-check").setLevel(NativeLevel.INFO)
        }
        _     <- logger.info { builds += 1; "enabled" }
        total <- reads.get
      } yield {
        assertEquals(before, 0)
        assertEquals(afterDisabled, 0)
        assertEquals(total, 1)
        assertEquals(encodes, 1)
        assertEquals(builds, 1)
        assertEquals(appender.list.size(), 1)
      }
    }
  }

  test("MDC-aware logging uses logical MDC and differs explicitly from native-thread checks") {
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
          if (org.slf4j.MDC.get("logical.request") == "req-42") ch.qos.logback.core.spi.FilterReply.ACCEPT
          else ch.qos.logback.core.spi.FilterReply.DENY
      })
      var encodes = 0
      val encoder = new Encoder[Int] {
        def encode[V](value: Int)(out: ValueEncoding[V]): V = { encodes += 1; out.int64(value.toLong) }
      }
      for {
        reads <- Ref.of[IO, Int](0)
        source = ContextSource(
          reads.update(_ + 1).as(LogContext(Mdc("logical.request" -> "req-42"), Fields(Field("value", 1)(encoder))))
        )
        aware = Slf4j.factory[IO](source, Slf4j.Config(native = native)).getLoggerFromName("aware")
        cheap = Slf4j
          .factory[IO](source, Slf4j.Config(native = native, enablement = Slf4j.Enablement.Native))
          .getLoggerFromName("native")
        _     <- aware.debug("accepted")
        _     <- cheap.debug("rejected")
        count <- reads.get
      } yield {
        assertEquals(count, 1)
        assertEquals(encodes, 1)
        assertEquals(appender.list.size(), 1)
      }
    }
  }

  test("Native disabled checks do not evaluate a failing context source") {
    val failure = new RuntimeException("context unavailable")
    recording.use { case (native, _) =>
      native.getLogger("disabled-source").setLevel(NativeLevel.ERROR)
      val source = ContextSource.fromFields(IO.raiseError[Fields](failure))
      val cheap = Slf4j
        .factory[IO](source, Slf4j.Config(native = native, enablement = Slf4j.Enablement.Native))
        .getLoggerFromName("disabled-source")
      val aware = Slf4j.factory[IO](source, Slf4j.Config(native = native)).getLoggerFromName("disabled-source")
      for {
        _      <- cheap.debug("disabled")
        failed <- aware.debug("disabled").attempt
      } yield assertEquals(failed, Left(failure): Either[Throwable, Unit])
    }
  }

  test("scoped logging captures once per execution, emits synchronously, and preserves both channels") {
    recording.use { case (native, appender) =>
      var version = 1
      for {
        reads <- Ref.of[IO, Int](0)
        local <- IOLocal(LogContext(Mdc("request.id" -> "first"), Fields(Field("request.id", 1))))
        source = ContextSource(reads.update(_ + 1) *> local.get.map { context =>
          context ++ LogContext(fields = Fields(Field("version", version)))
        })
        logging = Slf4j.scopedLogging[IO](source, Slf4j.Config(native = native))
        effect = logging.use("scoped") { logger =>
          val before = appender.list.size()
          logger.infoEvent("scoped.started")(
            Event
              .message("start")
              .withField("request.id", 42)
              .withMdc("request.id", "call")
              .withField("event.name", "ignored")
          )
          assertEquals(appender.list.size(), before + 1)
          version += 1
          logger.info("finish")
          assertEquals(appender.list.size(), before + 2)
          42
        }
        before <- reads.get
        first  <- effect
        _      <- local.set(LogContext(Mdc("request.id" -> "second"), Fields(Field("request.id", 2))))
        second <- effect
        total  <- reads.get
      } yield {
        assertEquals(before, 0)
        assertEquals(total, 2)
        assertEquals((first, second), (42, 42))
        val events = appender.list.asScala.toVector
        assertEquals(events.map(_.getMDCPropertyMap.get("request.id")), Vector("call", "first", "call", "second"))
        val fields = events.map(_.getKeyValuePairs.asScala.map(kv => kv.key -> kv.value).toMap)
        assertEquals(fields.map(_("version")), Vector(1L, 1L, 2L, 2L).map(java.lang.Long.valueOf))
        assertEquals(fields.map(_("request.id")), Vector(42L, 1L, 42L, 2L).map(java.lang.Long.valueOf))
        assertEquals(fields.head("event.name"), "scoped.started")
        assert(events.forall(_.getMDCPropertyMap.get("event.name") == null))
      }
    }
  }

  List(Slf4j.Enablement.MdcAware, Slf4j.Enablement.Native).foreach { enablement =>
    test(s"scoped $enablement calls recheck levels and skip disabled record construction and encoding") {
      recording.use { case (native, appender) =>
        val name         = classOf[String].getName
        val delegate     = native.getLogger(name)
        val cause        = new RuntimeException("original cause")
        var builds       = 0
        var encodes      = 0
        var strictCauses = 0
        val encoder = new Encoder[Int] {
          def encode[V](value: Int)(out: ValueEncoding[V]): V = { encodes += 1; out.int64(value.toLong) }
        }
        delegate.setLevel(NativeLevel.ERROR)
        Slf4j
          .scopedLogging[IO](Slf4j.Config(native = native, enablement = enablement))
          .use(classOf[String]) { logger =>
            logger.info { builds += 1; "disabled" }
            logger.info("disabled", { strictCauses += 1; cause })
            logger.debugEvent { builds += 1; Event.body(42)(encoder) }
            assertEquals(appender.list.size(), 0)
            delegate.setLevel(NativeLevel.TRACE)
            logger.trace { builds += 1; "trace" }
            logger.debugEvent("enabled")(Event.body(42)(encoder))
            logger.warn("warn", cause)
            logger.errorEvent(Event.exception(cause))
            logger.fatal("fatal")
          }
          .map { _ =>
            assertEquals(builds, 1)
            assertEquals(strictCauses, 1)
            assertEquals(encodes, 1)
            val events = appender.list.asScala.toVector
            assertEquals(
              events.map(_.getLevel),
              Vector(NativeLevel.TRACE, NativeLevel.DEBUG, NativeLevel.WARN, NativeLevel.ERROR, NativeLevel.ERROR)
            )
            assert(events.forall(_.getLoggerName == name))
            assert(events(2).getThrowableProxy.asInstanceOf[ThrowableProxy].getThrowable eq cause)
            assert(events(3).getThrowableProxy.asInstanceOf[ThrowableProxy].getThrowable eq cause)
          }
      }
    }
  }

  List(Slf4j.MdcPolicy.Isolate, Slf4j.MdcPolicy.Overlay).foreach { policy =>
    test(s"scoped $policy logging restores thread MDC and propagates failure after prior emissions") {
      recording.use { case (native, appender) =>
        IO {
          val previous = org.slf4j.MDC.getCopyOfContextMap
          try {
            org.slf4j.MDC.clear()
            org.slf4j.MDC.put("host", "thread")
            val source  = ContextSource.const[SyncIO](LogContext(mdc = Mdc("request.id" -> "logical")))
            val logging = Slf4j.scopedLogging[SyncIO](source, Slf4j.Config(native = native, mdcPolicy = policy))
            val failure = new RuntimeException("callback failure")
            val result = logging
              .use[Unit]("partial") { logger =>
                logger.info("before failure")
                assertEquals(org.slf4j.MDC.getCopyOfContextMap.asScala.toMap, Map("host" -> "thread"))
                throw failure
              }
              .attempt
              .unsafeRunSync()
            assertEquals(result, Left(failure): Either[Throwable, Unit])
            assertEquals(appender.list.size(), 1)
            val expected =
              if (policy == Slf4j.MdcPolicy.Overlay)
                Map("host"          -> "thread", "request.id" -> "logical")
              else Map("request.id" -> "logical")
            assertEquals(appender.list.get(0).getMDCPropertyMap.asScala.toMap, expected)
            assertEquals(org.slf4j.MDC.getCopyOfContextMap.asScala.toMap, Map("host" -> "thread"))
          } finally
            if (previous == null) org.slf4j.MDC.clear() else org.slf4j.MDC.setContextMap(previous)
        }
      }
    }
  }

  test("scoped context failures skip the callback; encoding failures prevent that record's emission") {
    recording.use { case (native, appender) =>
      val failure   = new RuntimeException("source or encoding failure")
      var callbacks = 0
      val failing =
        Slf4j.scopedLogging[IO](ContextSource(IO.raiseError[LogContext](failure)), Slf4j.Config(native = native))
      val encoder = new Encoder[Int] {
        def encode[V](value: Int)(out: ValueEncoding[V]): V = throw failure
      }
      for {
        capture <- failing
          .use("failed") { logger =>
            callbacks += 1
            logger.info("unreachable")
          }
          .attempt
        encoded <- Slf4j
          .scopedLogging[IO](Slf4j.Config(native = native))
          .use("encoding") { logger =>
            logger.infoEvent(Event.body(42)(encoder))
          }
          .attempt
      } yield {
        assertEquals(capture, Left(failure): Either[Throwable, Unit])
        assertEquals(encoded, Left(failure): Either[Throwable, Unit])
        assertEquals(callbacks, 0)
        assertEquals(appender.list.size(), 0)
      }
    }
  }

}
