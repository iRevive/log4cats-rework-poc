package org.typelevel.log4cats.experimental.pipeline

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all._
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.backend.{Backend, EncodedEvent}
import org.typelevel.log4cats.experimental.context.ContextSource
import org.typelevel.log4cats.experimental.value.{Value, ValueBackend}
import org.typelevel.log4cats.experimental.{Event, Field, Fields, Level}
import scala.concurrent.duration._

final class PipelineTopologySuite extends CatsEffectSuite {
  private def topology(combinedQueue: Boolean): IO[Unit] = for {
    entered <- Deferred[IO, Unit]
    release <- Deferred[IO, Unit]
    right   <- Ref.of[IO, Int](0)
    leftBackend = ValueBackend[IO](
      _ => entered.complete(()).void *> release.get,
      IO.pure(()),
      (_, _) => IO.pure(true)
    )
    rightBackend = ValueBackend[IO](
      _ => right.update(_ + 1),
      IO.pure(()),
      (_, _) => IO.pure(true)
    )
    a    = Pipeline.fromBackend[IO](Resource.pure[IO, Backend[IO]](leftBackend))
    b    = Pipeline.fromBackend[IO](Resource.pure[IO, Backend[IO]](rightBackend))
    plan = if (combinedQueue) (a ++ b).async(2) else a.async(2) ++ b
    _ <- Pipeline.runtimeResource(plan).use { runtime =>
      val logger = runtime.getLoggerFromName("topology")
      for {
        _      <- logger.info("event")
        _      <- entered.get
        before <- right.get
        _ <- IO(assertEquals(before, if (combinedQueue) 0 else 1))
          .guarantee(release.complete(()).void)
        _     <- runtime.flush
        after <- right.get
        _     <- IO(assertEquals(after, 1))
      } yield ()
    }
  } yield ()

  test("async(a ++ b) queues the compound delivery")(topology(true))
  test("async(a) ++ b keeps b synchronous")(topology(false))

  test("common and branch contexts remain distinct when the same source object is reused") {
    for {
      reads  <- Ref.of[IO, Int](0)
      events <- Ref.of[IO, Vector[EncodedEvent[Value]]](Vector.empty)
      source = ContextSource(
        reads.modify(n => (n + 1, Fields(Field("capture", n + 1))))
      )
      backend = ValueBackend[IO](
        e => events.update(_ :+ e),
        IO.pure(()),
        (_, _) => IO.pure(true)
      )
      output = Pipeline.fromBackend[IO](Resource.pure[IO, Backend[IO]](backend))
      plan   = output.withContextSource(source) ++ output.withContextSource(source)
      _        <- Pipeline.runtimeResource(plan).use(_.getLoggerFromName("separate-nodes").info("event"))
      recorded <- events.get
    } yield assertEquals(
      recorded.map(_.fields),
      Vector[Value](
        Value.Obj(Map("capture" -> Value.Int64(1))),
        Value.Obj(Map("capture" -> Value.Int64(2)))
      )
    )
  }

  test("a source shared by queued and direct outputs is captured once before either delivers") {
    for {
      reads  <- Ref.of[IO, Int](0)
      events <- Ref.of[IO, Vector[(String, Value)]](Vector.empty)
      source = ContextSource(reads.modify(n => (n + 1, Fields(Field("capture", n + 1)))))
      output = (name: String) =>
        Pipeline.fromBackend[IO](
          Resource.pure[IO, Backend[IO]](
            ValueBackend[IO](
              event => events.update(_ :+ (name -> event.fields)),
              IO.pure(()),
              (_, _) => IO.pure(true)
            )
          )
        )
      plan = (output("queued").async(2) ++ output("direct"))
        .withContextSource(source)
        .withFields(Fields(Field("static", true)))
      _ <- Pipeline.runtimeResource(plan).use { runtime =>
        runtime.getLoggerFromName("shared").info("event") *> runtime.flush
      }
      count    <- reads.get
      recorded <- events.get
    } yield {
      assertEquals(count, 1)
      val fields: Value = Value.Obj(Map("capture" -> Value.Int64(1), "static" -> Value.Bool(true)))
      assertEquals(recorded.toMap, Map("queued" -> fields, "direct" -> fields))
      assertEquals(recorded.size, 2)
    }
  }

  test("nested async boundaries flush through downstream queues") {
    for {
      events <- Ref.of[IO, Int](0)
      backend = ValueBackend[IO](
        _ => events.update(_ + 1),
        IO.pure(()),
        (_, _) => IO.pure(true)
      )
      plan = Pipeline.fromBackend[IO](Resource.pure[IO, Backend[IO]](backend)).async(2).async(2)
      _ <- Pipeline.runtimeResource(plan).use { runtime =>
        val logger = runtime.getLoggerFromName("nested")
        logger.info("one") *> logger.info("two") *> runtime.flush *>
          events.get.flatMap(n => IO(assertEquals(n, 2)))
      }
    } yield ()
  }

  test("late-rejected events do not occupy a full async queue") {
    for {
      entered <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      backend = ValueBackend[IO](
        _ => entered.complete(()).void *> release.get,
        IO.pure(()),
        (_, _) => IO.pure(true)
      )
      plan = Pipeline
        .fromBackend[IO](Resource.pure[IO, Backend[IO]](backend))
        .filterFields(_.contains("keep"))
        .async(1)
      _ <- Pipeline.runtimeResource(plan).use { runtime =>
        val logger = runtime.getLoggerFromName("no-rejected-admission")
        val kept   = Event.message("kept").withField("keep", true)
        (logger.infoEvent(kept) *> entered.get *> logger.infoEvent(kept) *>
          logger.info("rejected").timeout(1.second)).guarantee(release.complete(()).void)
      }
    } yield ()
  }

  test("acquisition failure is suspended and releases previously acquired resources") {
    val failure = new RuntimeException("acquisition failed")
    for {
      released <- Ref.of[IO, Boolean](false)
      backend  = ValueBackend[IO](_ => IO.unit, IO.pure(()), (_, _) => IO.pure(true))
      first    = Pipeline.fromBackend[IO](Resource.make(IO.pure[Backend[IO]](backend))(_ => released.set(true)))
      second   = Pipeline.fromBackend[IO](throw failure)
      resource = Pipeline.runtimeResource(first ++ second)
      before <- released.get
      result <- resource.use(_ => IO.unit).attempt
      after  <- released.get
    } yield {
      assert(!before)
      assertEquals(result, Left(failure): Either[Throwable, Unit])
      assert(after)
    }
  }

  test("later defaults win even when other wrappers intervene") {
    for {
      events <- Ref.of[IO, Vector[EncodedEvent[Value]]](Vector.empty)
      backend = ValueBackend[IO](
        e => events.update(_ :+ e),
        IO.pure(()),
        (_, _) => IO.pure(true)
      )
      plan = Pipeline
        .fromBackend[IO](Resource.pure[IO, Backend[IO]](backend))
        .withFields(Fields(Field("key", "first")))
        .atLeast(Level.Info)
        .withFields(Fields(Field("key", "second")))
      _      <- Pipeline.runtimeResource(plan).use(_.getLoggerFromName("defaults").info("event"))
      values <- events.get
    } yield assertEquals(values.head.fields, Value.Obj(Map("key" -> Value.Text("second"))): Value)
  }
}
