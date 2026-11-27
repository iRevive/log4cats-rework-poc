package org.typelevel.log4cats.experimental.pipeline

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all._
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.backend.{Backend, BoundBackend, EncodedEvent}
import org.typelevel.log4cats.experimental.console.ConsoleBackend
import org.typelevel.log4cats.experimental.value.{Value, ValueBackend}
import org.typelevel.log4cats.experimental.{LoggerFactory, Scope}

final class LoggerFactorySuite extends CatsEffectSuite {
  test("all factory methods create lightweight loggers over one acquired destination") {
    for {
      acquisitions <- Ref.of[IO, Int](0)
      releases     <- Ref.of[IO, Int](0)
      captures     <- Ref.of[IO, Int](0)
      events       <- Ref.of[IO, Vector[EncodedEvent[Value]]](Vector.empty)
      backend = ValueBackend[IO](
        e => events.update(_ :+ e),
        captures.update(_ + 1).as(()),
        (_, _) => IO.pure(true)
      )
      plan = Pipeline.fromBackend[IO](
        Resource.make(acquisitions.update(_ + 1).as[Backend[IO]](backend))(_ => releases.update(_ + 1))
      )
      _ <- Pipeline.resource(plan).use { factory =>
        implicit val loggerFactory: LoggerFactory[IO] = factory
        for {
          byName  <- LoggerFactory[IO].fromName("safe")
          byClass <- LoggerFactory[IO].fromClass(classOf[String])
          unsafeName  = LoggerFactory[IO].fromNameUnsafe("unsafe")
          unsafeClass = LoggerFactory[IO].fromClassUnsafe(classOf[Int])
          before <- (acquisitions.get, releases.get, captures.get, events.get).tupled
          _      <- IO(assertEquals(before, (1, 0, 0, Vector.empty[EncodedEvent[Value]])))
          _ <- byName.info("safe") *> byClass.info("class") *> unsafeName.info("unsafe") *> unsafeClass.info(
            "unsafe class"
          )
        } yield ()
      }
      counts   <- (acquisitions.get, releases.get, captures.get).tupled
      recorded <- events.get
    } yield {
      assertEquals(counts, (1, 1, 4))
      assertEquals(
        recorded.map(_.meta.scope.name),
        Vector("safe", classOf[String].getName, "unsafe", classOf[Int].getName)
      )
    }
  }

  test("safe factory methods use effectful binding and retain handles across logging calls") {
    val safeBinds   = new java.util.concurrent.atomic.AtomicInteger(0)
    val unsafeBinds = new java.util.concurrent.atomic.AtomicInteger(0)
    val output      = ValueBackend[IO](_ => IO.unit, IO.pure(()), (_, _) => IO.pure(true)).bindUnsafe(Scope("test"))
    val backend = new Backend[IO] {
      def bind(scope: Scope): IO[BoundBackend[IO]] =
        IO { safeBinds.incrementAndGet(); output }
      def bindUnsafe(scope: Scope): BoundBackend[IO] = { unsafeBinds.incrementAndGet(); output }
    }
    Pipeline.resource(Pipeline.fromBackend[IO](Resource.pure[IO, Backend[IO]](backend))).use { factory =>
      for {
        first  <- factory.fromName("first")
        second <- factory.fromClass(classOf[String])
        third  = factory.fromNameUnsafe("third")
        fourth = factory.fromClassUnsafe(classOf[Int])
        _ <- first.info("one") *> first.info("again") *> second.info("two") *> third.info("three") *> fourth.info(
          "four"
        )
      } yield {
        assertEquals(safeBinds.get(), 2)
        assertEquals(unsafeBinds.get(), 2)
      }
    }
  }

  test("safe construction suspends validation while unsafe construction validates immediately") {
    val plan = Pipeline.fromBackend[IO](Resource.pure[IO, Backend[IO]](ConsoleBackend.stdout[IO]))
    Pipeline.resource(plan).use { factory =>
      val invalidName  = factory.fromName("")
      val invalidClass = factory.fromClass(null)
      intercept[IllegalArgumentException](factory.fromNameUnsafe(""))
      intercept[NullPointerException](factory.fromClassUnsafe(null))
      for {
        nameResult  <- invalidName.attempt
        classResult <- invalidClass.attempt
      } yield {
        assert(nameResult.swap.exists(_.isInstanceOf[IllegalArgumentException]))
        assert(classResult.swap.exists(_.isInstanceOf[NullPointerException]))
      }
    }
  }

  test("named loggers share the same async worker, ordering and flush barrier") {
    for {
      entered  <- Deferred[IO, Unit]
      release  <- Deferred[IO, Unit]
      recorded <- Ref.of[IO, Vector[String]](Vector.empty)
      backend = ValueBackend[IO](
        event =>
          (if (event.meta.scope.name == "first") entered.complete(()).void *> release.get else IO.unit) *>
            recorded.update(_ :+ event.meta.scope.name),
        IO.pure(()),
        (_, _) => IO.pure(true)
      )
      plan = Pipeline.fromBackend[IO](Resource.pure[IO, Backend[IO]](backend)).async(1)
      _ <- Pipeline.runtimeResource(plan).use { runtime =>
        val first  = runtime.fromNameUnsafe("first")
        val second = runtime.fromNameUnsafe("second")
        (for {
          _      <- first.info("one")
          _      <- entered.get
          _      <- second.info("two")
          before <- recorded.get
          _      <- IO(assertEquals(before, Vector.empty[String]))
          _      <- release.complete(())
          _      <- runtime.flush
          after  <- recorded.get
        } yield assertEquals(after, Vector("first", "second"))).guarantee(release.complete(()).void)
      }
    } yield ()
  }
}
