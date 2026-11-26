package org.typelevel.log4cats.experimental.pipeline.internal

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all._
import munit.CatsEffectSuite
import scala.concurrent.duration._

final class AsyncDeliveryQueueSuite extends CatsEffectSuite {
  test("admission completes before delivery, flush is a barrier, finalization drains") {
    for {
      entered <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      events  <- Ref.of[IO, Vector[Int]](Vector.empty)
      _ <- AsyncDeliveryQueue.resource[IO](2).use { queue =>
        for {
          _      <- queue.submit(entered.complete(()).void *> release.get *> events.update(_ :+ 1))
          _      <- entered.get
          _      <- queue.submit(events.update(_ :+ 2))
          before <- events.get
          _      <- IO(assertEquals(before, Vector.empty[Int]))
          _      <- release.complete(())
          _      <- queue.flush
          after  <- events.get
          _      <- IO(assertEquals(after, Vector(1, 2)))
          _      <- queue.submit(events.update(_ :+ 3))
        } yield ()
      }
      all <- events.get
    } yield assertEquals(all, Vector(1, 2, 3))
  }

  test("worker failure is visible through flush, submissions and resource release") {
    val failure = new RuntimeException("destination failed")
    for {
      result <- AsyncDeliveryQueue
        .resource[IO](1)
        .use { queue =>
          for {
            _      <- queue.submit(IO.raiseError[Unit](failure))
            flush  <- queue.flush.attempt
            submit <- queue.submit(IO.unit).attempt
            _ <- IO {
              assertEquals(flush, Left(failure): Either[Throwable, Unit])
              assertEquals(submit, Left(failure): Either[Throwable, Unit])
            }
          } yield ()
        }
        .attempt
    } yield assertEquals(result, Left(failure): Either[Throwable, Unit])
  }

  test("cancellation of a blocked producer releases admission for later submissions") {
    for {
      entered    <- Deferred[IO, Unit]
      release    <- Deferred[IO, Unit]
      attempting <- Deferred[IO, Unit]
      events     <- Ref.of[IO, Vector[Int]](Vector.empty)
      _ <- AsyncDeliveryQueue.resource[IO](1).use { queue =>
        for {
          _       <- queue.submit(entered.complete(()).void *> release.get)
          _       <- entered.get
          _       <- queue.submit(events.update(_ :+ 1))
          blocked <- (attempting.complete(()) *> queue.submit(events.update(_ :+ 2))).start
          _       <- attempting.get
          _       <- blocked.cancel
          _       <- release.complete(())
          _       <- queue.submit(events.update(_ :+ 3))
          _       <- queue.flush
        } yield ()
      }
      all <- events.get
    } yield assertEquals(all, Vector(1, 3))
  }

  test("shutdown closes admission, drains existing work, and rejects later use") {
    for {
      entered   <- Deferred[IO, Unit]
      release   <- Deferred[IO, Unit]
      events    <- Ref.of[IO, Int](0)
      allocated <- AsyncDeliveryQueue.resource[IO](1).allocated
      (queue, close) = allocated
      _       <- queue.submit(entered.complete(()).void *> release.get *> events.update(_ + 1))
      _       <- entered.get
      _       <- queue.submit(events.update(_ + 1))
      closing <- close.start
      _       <- release.complete(())
      _       <- closing.joinWithNever
      late    <- queue.submit(IO.unit).attempt
      all     <- events.get
    } yield {
      assert(late.swap.exists(_.isInstanceOf[AsyncDeliveryQueue.Closed]))
      assertEquals(all, 2)
    }
  }

  test("a self-canceling backend does not leave flush or shutdown waiting forever") {
    for {
      allocated <- AsyncDeliveryQueue.resource[IO](1).allocated
      (queue, close) = allocated
      _       <- queue.submit(IO.canceled)
      flushed <- queue.flush.attempt
      closed  <- close.attempt
    } yield {
      assert(flushed.swap.exists(_.isInstanceOf[AsyncDeliveryQueue.WorkerCanceled]))
      assert(closed.swap.exists(_.isInstanceOf[AsyncDeliveryQueue.WorkerCanceled]))
    }
  }

  test("drain timeout cancels an unresponsive cancelable destination") {
    for {
      entered   <- Deferred[IO, Unit]
      cancelled <- Deferred[IO, Unit]
      result <- AsyncDeliveryQueue
        .resource[IO](1, 100.millis)
        .use { queue =>
          queue.submit(
            (entered.complete(()) *> IO.never[Unit]).onCancel(cancelled.complete(()).void)
          ) *>
            entered.get
        }
        .attempt
      _ <- cancelled.get
    } yield assert(result.swap.exists(_.isInstanceOf[AsyncDeliveryQueue.DrainTimedOut]))
  }
}
