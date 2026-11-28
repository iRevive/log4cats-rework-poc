package consumer

import cats.~>
import cats.data.Kleisli
import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.{Level, RecordWriter, ScopedLogger, ScopedLogging}

final class ScopedLoggingSuite extends CatsEffectSuite {
  test("implicit capability and mapK preserve suspended callbacks, names, results, and replay") {
    type Reader[A] = Kleisli[IO, Unit, A]
    var names     = Vector.empty[String]
    var logs      = Vector.empty[Level]
    var callbacks = 0
    val logger = new ScopedLogger {
      def log(level: Level, eventName: Option[String], write: RecordWriter => Unit): Unit =
        logs :+= level
    }
    implicit val capability: ScopedLogging[IO] = new ScopedLogging[IO] {
      def use[A](name: String)(f: ScopedLogger => A): IO[A] = IO {
        names :+= name
        f(logger)
      }
    }
    val mapped = ScopedLogging[IO].mapK(new (IO ~> Reader) {
      def apply[A](fa: IO[A]): Reader[A] = Kleisli.liftF(fa)
    })
    val effect = mapped.use(classOf[String]) { scoped =>
      scoped.info("synchronous")
      callbacks += 1
      assertEquals(logs.size, callbacks)
      42
    }
    assertEquals(callbacks, 0)
    assert(names.isEmpty)
    for {
      first  <- effect.run(())
      second <- effect.run(())
    } yield {
      assertEquals((first, second), (42, 42))
      assertEquals(names, Vector.fill(2)(classOf[String].getName))
      assertEquals(logs, Vector.fill(2)(Level.Info))
      assertEquals(callbacks, 2)
    }
  }

  test("mapK preserves callback failure without executing a second callback") {
    type Reader[A] = Kleisli[IO, Unit, A]
    val failure = new RuntimeException("callback failed")
    var calls   = 0
    val capability = new ScopedLogging[IO] {
      def use[A](name: String)(f: ScopedLogger => A): IO[A] = IO {
        calls += 1
        f(new ScopedLogger {
          def log(level: Level, eventName: Option[String], write: RecordWriter => Unit): Unit = ()
        })
      }
    }
    val mapped = capability.mapK(new (IO ~> Reader) {
      def apply[A](fa: IO[A]): Reader[A] = Kleisli.liftF(fa)
    })
    mapped.use[Unit]("failure")(_ => throw failure).run(()).attempt.map { result =>
      assertEquals(result, Left(failure): Either[Throwable, Unit])
      assertEquals(calls, 1)
    }
  }
}
