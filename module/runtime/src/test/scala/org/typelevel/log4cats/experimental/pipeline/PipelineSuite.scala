package org.typelevel.log4cats.experimental.pipeline

import cats.effect.{Clock, IO, Ref, Resource, Sync}
import cats.syntax.all._
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.backend.{Backend, EncodedEvent, EventMeta}
import org.typelevel.log4cats.experimental.context.{ContextSource, LogContext}
import org.typelevel.log4cats.experimental.encoding.{Encoder, ValueEncoding}
import org.typelevel.log4cats.experimental.value.{Value, ValueBackend}
import org.typelevel.log4cats.experimental.{Field, Fields, Level, LoggerFactory}
import org.typelevel.vault.Vault
import scala.concurrent.duration._

final class PipelineSuite extends CatsEffectSuite {
  private def backend(
      write: EncodedEvent[Value] => IO[Unit],
      capture: IO[Vault] = IO.pure(Vault.empty),
      accepts: (EventMeta, Vault) => IO[Boolean] = (_, _) => IO.pure(true)
  ): Backend[IO] = ValueBackend[IO](write, capture, accepts)

  private def factoryResource(
      pipeline: Pipeline[IO],
      context: ContextSource[IO] = ContextSource.empty[IO]
  ): Resource[IO, LoggerFactory[IO]] =
    Pipeline.resource(pipeline, context)

  test("fan-out applies branch thresholds and invokes one writer for selected outputs") {
    for {
      outputs <- Ref.of[IO, Vector[(String, Value)]](Vector.empty)
      left = Pipeline
        .fromBackend[IO](Resource.pure(backend(e => outputs.update(_ :+ ("left" -> e.body)))))
        .atLeast(Level.Warn)
      right = Pipeline
        .fromBackend[IO](Resource.pure(backend(e => outputs.update(_ :+ ("right" -> e.body)))))
        .atLeast(Level.Debug)
      writes = new java.util.concurrent.atomic.AtomicInteger(0)
      _ <- factoryResource(left ++ right).use { factory =>
        val logger = factory.fromNameUnsafe("fanout")
        logger.log(
          Level.Info,
          None,
          writer => { writes.incrementAndGet(); writer.body("once") }
        )
      }
      seen <- outputs.get
    } yield {
      assertEquals(writes.get(), 1)
      assertEquals(seen, Vector("right" -> Value.Text("once")))
    }
  }

  test(
    "shared sources capture once, branch sources stay branch-local, and fields use right precedence"
  ) {
    for {
      leftFields  <- Ref.of[IO, Value](Value.Empty)
      rightFields <- Ref.of[IO, Value](Value.Empty)
      sharedCount <- Ref.of[IO, Int](0)
      leftCount   <- Ref.of[IO, Int](0)
      rightCount  <- Ref.of[IO, Int](0)
      shared = ContextSource[IO](
        sharedCount.update(_ + 1).as(LogContext(Fields(Field("shared", 1))))
      )
      leftCtx = ContextSource[IO](
        leftCount.update(_ + 1).as(LogContext(Fields(Field("key", "left-ambient"))))
      )
      rightCtx = ContextSource[IO](
        rightCount.update(_ + 1).as(LogContext(Fields(Field("key", "right-ambient"))))
      )
      left = Pipeline
        .fromBackend[IO](Resource.pure(backend(e => leftFields.set(e.fields))))
        .withContextSource(leftCtx)
        .withFields(Fields(Field("key", "left-default")))
      right = Pipeline
        .fromBackend[IO](Resource.pure(backend(e => rightFields.set(e.fields))))
        .withContextSource(rightCtx)
        .withFields(Fields(Field("key", "right-default")))
      pipeline = (left ++ right)
        .withContextSource(shared)
        .withFields(Fields(Field("root", true)))
        .withFields(Fields(Field("root", false)))
      callWins <- factoryResource(pipeline).use { factory =>
        val logger = factory.fromNameUnsafe("fields")
        for {
          _     <- logger.log(Level.Info, None, w => w.field("key", "call"))
          first <- (leftFields.get, rightFields.get).tupled
          _     <- logger.log(Level.Info, None, _ => ())
        } yield first
      }
      values <- (
        leftFields.get,
        rightFields.get,
        sharedCount.get,
        leftCount.get,
        rightCount.get
      ).tupled
    } yield {
      val callFields = Value.Obj(
        Map("root" -> Value.Bool(false), "shared" -> Value.Int64(1), "key" -> Value.Text("call"))
      )
      val leftAmbient = Value.Obj(
        Map(
          "root"   -> Value.Bool(false),
          "shared" -> Value.Int64(1),
          "key"    -> Value.Text("left-ambient")
        )
      )
      val rightAmbient = Value.Obj(
        Map(
          "root"   -> Value.Bool(false),
          "shared" -> Value.Int64(1),
          "key"    -> Value.Text("right-ambient")
        )
      )
      assertEquals(callWins, (callFields, callFields))
      assertEquals((values._1, values._2), (leftAmbient, rightAmbient))
      assertEquals(values._3, 2)
      assertEquals(values._4, 2)
      assertEquals(values._5, 2)
    }
  }

  test(
    "metadata gates skip source and callback; field gates run after one callback and isolate branches"
  ) {
    for {
      captures <- Ref.of[IO, Int](0)
      calls = new java.util.concurrent.atomic.AtomicInteger(0)
      left  <- Ref.of[IO, Int](0)
      right <- Ref.of[IO, Int](0)
      src = ContextSource[IO](captures.update(_ + 1).as(LogContext()))
      leftPipeline = Pipeline
        .fromBackend[IO](Resource.pure(backend(_ => left.update(_ + 1))))
        .filterMetadata((level, name) => level == Level.Info && name.contains("kept"))
        .filterFields(_.values.exists(_.name == "keep"))
        .withContextSource(src)
      rightPipeline = Pipeline
        .fromBackend[IO](Resource.pure(backend(_ => right.update(_ + 1))))
        .filterMetadata((level, name) => level == Level.Info && name.contains("kept"))
        .filterFields(_.values.exists(_.name == "drop"))
      afterCoarse <- factoryResource(leftPipeline ++ rightPipeline).use { factory =>
        val logger = factory.fromNameUnsafe("filters")
        for {
          _      <- logger.log(Level.Debug, Some("kept"), _ => { calls.incrementAndGet(); () })
          before <- captures.get
          _ <- logger.log(
            Level.Info,
            Some("kept"),
            w => { calls.incrementAndGet(); w.field("keep", true) }
          )
        } yield before
      }
      counts <- (captures.get, left.get, right.get).tupled
    } yield {
      assertEquals(afterCoarse, 0)
      assertEquals(calls.get(), 1)
      assertEquals(counts, (1, 1, 0))
    }
  }

  test("all selected encodings finish before delivery starts") {
    var encodes = 0
    val broken = new Encoder[String] {
      def encode[V](value: String)(out: ValueEncoding[V]): V = {
        encodes += 1
        if (encodes == 2) throw new RuntimeException("encode failed")
        out.text(value)
      }
    }
    for {
      emissions <- Ref.of[IO, Int](0)
      output = Pipeline.fromBackend[IO](Resource.pure(backend(_ => emissions.update(_ + 1))))
      result <- factoryResource(output ++ output).use { factory =>
        val logger = factory.fromNameUnsafe("encoding")
        logger.log(Level.Info, None, w => w.body("encoded")(broken))
      }.attempt
      delivered <- emissions.get
    } yield {
      assert(result.isLeft)
      assertEquals(encodes, 2)
      assertEquals(delivered, 0)
    }
  }

  test("compiled filters preserve inner-to-outer order and the strongest configured minimum") {
    var steps = Vector.empty[String]
    def metadata(label: String): (Level, Option[String]) => Boolean = (_, _) => {
      steps :+= label
      true
    }
    def fields(label: String): Fields => Boolean = _ => {
      steps :+= label
      true
    }
    val source = ContextSource(IO { steps :+= "context"; LogContext() })
    val output = Pipeline
      .fromBackend[IO](Resource.pure(backend(_ => IO.unit)))
      .atLeast(Level.Warn)
      .filterMetadata(metadata("inner metadata"))
      .filterFields(fields("inner fields"))
      .withFields(Fields(Field("static", true)))
      .withContextSource(source)
      .atLeast(Level.Info)
      .filterMetadata(metadata("outer metadata"))
      .filterFields(fields("outer fields"))
    Pipeline.resource(output).use { factory =>
      val logger = factory.fromNameUnsafe("compiled-filters")
      logger.info("rejected") *> IO(assertEquals(steps, Vector.empty[String])) *>
        logger.log(Level.Warn, None, writer => { steps :+= "writer"; writer.body("accepted") }) *>
        IO(
          assertEquals(
            steps,
            Vector("inner metadata", "outer metadata", "context", "writer", "inner fields", "outer fields")
          )
        )
    }
  }

  test("deep configuration compiles and fan-out still constructs one record") {
    for {
      events <- Ref.of[IO, Int](0)
      writes      = new java.util.concurrent.atomic.AtomicInteger(0)
      destination = Pipeline.fromBackend[IO](Resource.pure(backend(_ => events.update(_ + 1))))
      fanout      = (1 until 128).foldLeft(destination)((plan, _) => plan ++ destination)
      configured  = (0 until 10000).foldLeft(fanout)((plan, _) => plan.atLeast(Level.Info))
      _ <- Pipeline.runtimeResource(configured).use { runtime =>
        val logger = runtime.fromNameUnsafe("deep")
        logger.log(Level.Info, None, writer => { writes.incrementAndGet(); writer.body("accepted") }) *> runtime.flush
      }
      count <- events.get
      calls = writes.get()
    } yield {
      assertEquals(count, 128)
      assertEquals(calls, 1)
    }
  }

  test("an injected clock owns occurrence time and coarse minimum rejection reads no context") {
    for {
      clockReads   <- Ref.of[IO, Int](0)
      contextReads <- Ref.of[IO, Int](0)
      times        <- Ref.of[IO, Vector[Long]](Vector.empty)
      suppliedClock = new Clock[IO] {
        def applicative: cats.Applicative[IO] = implicitly[cats.Applicative[IO]]
        def realTime: IO[FiniteDuration]      = clockReads.update(_ + 1).as(123L.nanos)
        def monotonic: IO[FiniteDuration]     = IO.pure(456L.nanos)
      }
      context = ContextSource[IO](contextReads.update(_ + 1).as(LogContext()))
      output = Pipeline
        .fromBackend[IO](Resource.pure(backend(e => times.update(_ :+ e.meta.timestampNanos))))
        .atLeast(Level.Info)
      resource = Pipeline.resource(output, context)(Sync[IO], suppliedClock)
      _ <- resource.use { factory =>
        val logger = factory.fromNameUnsafe("clock")
        logger.debug("disabled") *> (clockReads.get, contextReads.get).tupled.flatMap { before =>
          IO(assertEquals(before, (0, 0))) *> logger.info("accepted")
        }
      }
      seen <- (clockReads.get, contextReads.get, times.get).tupled
    } yield {
      assertEquals(seen._1, 1)
      assertEquals(seen._2, 1)
      assertEquals(seen._3, Vector(123L))
    }
  }
}
