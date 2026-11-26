package consumer

import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.Body
import org.typelevel.log4cats.experimental.encoding.{Encoder, ValueEncoding}
import org.typelevel.log4cats.experimental.value.Value
import org.typelevel.log4cats.experimental.{Event, Fields, Level, Logger, RecordWriter}

final class InlineFrontendSuite extends CatsEffectSuite {
  test("expansions are hygienic when the consumer shadows String and Throwable") {
    type String    = Int
    type Throwable = Int
    val writer = new RecordingWriter
    val logger: Logger[IO] =
      (_: Level, _: Option[java.lang.String], write: RecordWriter => Unit) => IO(write(writer))
    val cause = new RuntimeException("failure")
    logger.info("text", cause).map { _ =>
      assertEquals(writer.bodies, Vector(Value.Text("text")))
      assertEquals(writer.causes, Vector(cause))
    }
  }

  private final class RecordingWriter extends RecordWriter {
    var bodies                                                = Vector.empty[Value]
    var writes                                                = Vector.empty[String]
    var causes                                                = Vector.empty[Throwable]
    def body[A](value: A)(implicit encoder: Encoder[A]): Unit = body(Body(value))
    def body(value: Body): Unit = {
      writes :+= "body"
      bodies :+= value.encode(ValueEncoding.value)
    }
    def field[A](name: String, value: A)(implicit encoder: Encoder[A]): Unit =
      writes :+= name
    def fields(value: Fields): Unit = writes :+= "fields"
    def cause(value: Throwable): Unit = {
      writes :+= "cause"
      causes :+= value
    }
  }

  test("all helpers compile externally, retain SAM construction, and preserve severity") {
    val writer = new RecordingWriter
    var levels = Vector.empty[Level]
    var names  = Vector.empty[Option[String]]
    val cause  = new RuntimeException("cause")
    val logger: Logger[IO] =
      (level: Level, name: Option[String], write: RecordWriter => Unit) =>
        IO { levels :+= level; names :+= name; write(writer) }
    val calls = List(
      logger.trace("trace"),
      logger.debug("debug"),
      logger.info("info"),
      logger.warn("warn"),
      logger.error("error"),
      logger.fatal("fatal"),
      logger.traceEvent(Event.message("trace")),
      logger.debugEvent(Event.message("debug")),
      logger.infoEvent(Event.message("info")),
      logger.warnEvent(Event.message("warn")),
      logger.errorEvent(Event.message("error")),
      logger.fatalEvent(Event.message("fatal")),
      logger.trace("trace", cause),
      logger.debug("debug", cause),
      logger.info("info", cause),
      logger.warn("warn", cause),
      logger.error("error", cause),
      logger.fatal("fatal", cause),
      logger.traceEvent("trace.event")(Event.message("trace")),
      logger.debugEvent("debug.event")(Event.message("debug")),
      logger.infoEvent("info.event")(Event.message("info")),
      logger.warnEvent("warn.event")(Event.message("warn")),
      logger.errorEvent("error.event")(Event.message("error")),
      logger.fatalEvent("fatal.event")(Event.message("fatal"))
    )
    calls.foldLeft(IO.unit)(_ *> _).map { _ =>
      val expected =
        Vector(Level.Trace, Level.Debug, Level.Info, Level.Warn, Level.Error, Level.Fatal)
      assertEquals(levels, Vector.fill(4)(expected).flatten)
      assertEquals(
        names,
        Vector.fill[Option[String]](18)(None) ++ Vector("trace", "debug", "info", "warn", "error", "fatal")
          .map(name => Some(name + ".event"))
      )
      assertEquals(writer.causes, Vector.fill(6)(cause))
      assertEquals(writer.bodies.size, 24)
      val expectedWrites = Vector.fill(6)("body") ++
        Vector.fill(6)(Vector("body", "fields")).flatten ++
        Vector.fill(6)(Vector("body", "cause")).flatten ++
        Vector.fill(6)(Vector("body", "fields")).flatten
      assertEquals(writer.writes, expectedWrites)
    }
  }

  test("cause expressions are strict, ordered after the receiver, and retained across executions") {
    var order              = Vector.empty[String]
    var created: Throwable = null
    val writer             = new RecordingWriter
    def receiver(): Logger[IO] = {
      order :+= "receiver"
      (_: Level, _: Option[String], write: RecordWriter => Unit) => {
        order :+= "submit"
        IO(write(writer))
      }
    }
    def cause(): Throwable = {
      order :+= "cause"
      created = new RuntimeException("call-site cause")
      created
    }
    val effect   = receiver().error({ order :+= "message"; "failed" }, cause())
    val original = created
    val stack    = original.getStackTrace.toVector
    assertEquals(order, Vector("receiver", "cause", "submit"))
    assertEquals(writer.causes, Vector.empty[Throwable])
    (effect *> effect).map { _ =>
      assertEquals(order, Vector("receiver", "cause", "submit", "message", "message"))
      assert(writer.causes.forall(_ eq original))
      assertEquals(writer.causes.size, 2)
      assertEquals(original.getStackTrace.toVector, stack)
      assertEquals(writer.bodies, Vector.fill(2)(Value.Text("failed")))
    }
  }

  test("rejection skips a message but still evaluates its strict cause") {
    var messages             = 0
    var causes               = 0
    val failure              = new RuntimeException("cause")
    val disabled: Logger[IO] = (_: Level, _: Option[String], _: RecordWriter => Unit) => IO.unit
    val effect               = disabled.warn({ messages += 1; "skipped" }, { causes += 1; failure })
    assertEquals(causes, 1)
    assertEquals(messages, 0)
    val thrown = intercept[RuntimeException](disabled.error("unreachable", throw failure))
    assert(thrown eq failure)
    (effect *> effect).map { _ =>
      assertEquals(causes, 1)
      assertEquals(messages, 0)
    }
  }

  test("named event helpers capture the receiver and name once while deferring each event") {
    var order  = Vector.empty[String]
    var names  = Vector.empty[Option[String]]
    val writer = new RecordingWriter
    def receiver(): Logger[IO] = {
      order :+= "receiver"
      (_: Level, name: Option[String], write: RecordWriter => Unit) => {
        order :+= "submit"
        IO { names :+= name; write(writer) }
      }
    }
    def name(): String = { order :+= "name"; "named.event" }
    val effect         = receiver().infoEvent(name()) { order :+= "event"; Event.message("body") }
    assertEquals(order, Vector("receiver", "name", "submit"))
    (effect *> effect).map { _ =>
      assertEquals(order, Vector("receiver", "name", "submit", "event", "event"))
      assertEquals(names, Vector.fill(2)(Some("named.event")))
    }
  }

  test("message and event expressions remain lazy and are evaluated on every execution") {
    val writer   = new RecordingWriter
    var messages = 0
    var events   = 0
    val logger: Logger[IO] =
      (_: Level, _: Option[String], write: RecordWriter => Unit) => IO(write(writer))
    val message = logger.info { messages += 1; messages.toString }
    val event   = logger.infoEvent { events += 1; Event.message(events.toString) }
    assertEquals(messages, 0)
    assertEquals(events, 0)
    (message *> message *> event *> event).map { _ =>
      assertEquals(messages, 2)
      assertEquals(events, 2)
      assertEquals(
        writer.bodies,
        Vector(Value.Text("1"), Value.Text("2"), Value.Text("1"), Value.Text("2"))
      )
    }
  }

  test("rejection skips message and event exceptions; acceptance raises them inside IO") {
    val failure              = new RuntimeException("deferred")
    val disabled: Logger[IO] = (_: Level, _: Option[String], _: RecordWriter => Unit) => IO.unit
    val enabled: Logger[IO] =
      (_: Level, _: Option[String], write: RecordWriter => Unit) => IO(write(new RecordingWriter))
    val message = enabled.info(throw failure)
    val event   = enabled.infoEvent((throw failure): Event)
    for {
      _ <- disabled.info(throw failure)
      _ <- disabled.infoEvent((throw failure): Event)
      _ <- disabled.infoEvent("rejected")((throw failure): Event)
      a <- message.attempt
      b <- event.attempt
    } yield {
      assertEquals(a, Left(failure))
      assertEquals(b, Left(failure))
    }
  }

  test("receiver, level and eventName evaluate once in order before the deferred event") {
    var order  = Vector.empty[String]
    val writer = new RecordingWriter
    def receiver(): Logger[IO] = {
      order :+= "receiver"
      (_: Level, _: Option[String], write: RecordWriter => Unit) => IO(write(writer))
    }
    def level(): Level         = { order :+= "level"; Level.Info }
    def name(): Option[String] = { order :+= "name"; Some("event") }
    val effect = receiver().logEvent(level(), name()) {
      order :+= "event"
      Event.message("body")
    }
    assertEquals(order, Vector("receiver", "level", "name"))
    (effect *> effect).map(_ => assertEquals(order, Vector("receiver", "level", "name", "event", "event")))
  }

  test(
    "event construction finishes before writer interpretation; prebuilt events remain reusable"
  ) {
    val writer = new RecordingWriter
    val logger: Logger[IO] =
      (_: Level, _: Option[String], write: RecordWriter => Unit) => IO(write(writer))
    val failure  = new RuntimeException("field")
    val failing  = logger.infoEvent(Event.message("body").withField[String]("key", throw failure))
    val prebuilt = Event.message("stable").withField("key", 1)
    for {
      result <- failing.attempt
      _      <- IO { assertEquals(result, Left(failure)); assertEquals(writer.writes, Vector.empty) }
      _      <- logger.logEvent(Level.Info)(prebuilt)
      _      <- logger.logEvent(Level.Info)(prebuilt)
    } yield assertEquals(writer.bodies, Vector(Value.Text("stable"), Value.Text("stable")))
  }

  test("message receiver is evaluated once and a user by-name wrapper preserves laziness") {
    var receivers = 0
    var messages  = 0
    val writer    = new RecordingWriter
    def receiver(): Logger[IO] = {
      receivers += 1
      (_: Level, _: Option[String], write: RecordWriter => Unit) => IO(write(writer))
    }
    def wrapper(message: => String): IO[Unit] = receiver().info(message)
    val effect                                = wrapper { messages += 1; "wrapped" }
    assertEquals(receivers, 1)
    assertEquals(messages, 0)
    (effect *> effect).map { _ =>
      assertEquals(receivers, 1)
      assertEquals(messages, 2)
    }
  }
}
