package org.typelevel.log4cats.experimental.pipeline.internal

import cats.effect.std.{Mutex, Queue}
import cats.effect.syntax.all._
import cats.effect.{Async, Deferred, Outcome, Ref, Resource}
import cats.syntax.all._
import scala.concurrent.duration._

/** A fail-stop, bounded delivery queue. Successful submit acknowledges admission only. */
private[pipeline] final class AsyncDeliveryQueue[F[_]] private (
    queue: Queue[F, AsyncDeliveryQueue.Command[F]],
    admission: Mutex[F],
    stopped: Deferred[F, Throwable],
    failed: Ref[F, Option[Throwable]],
    finished: Deferred[F, Unit]
)(implicit F: Async[F]) {
  import AsyncDeliveryQueue._

  private def offer(command: Command[F]): F[Unit] = F.uncancelable { poll =>
    poll(admission.lock.surround {
      stopped.tryGet.flatMap {
        case Some(error) => F.raiseError[Unit](error)
        case None =>
          F.race(stopped.get, queue.offer(command)).flatMap {
            case Left(error) => F.raiseError[Unit](error)
            case Right(_)    => F.unit
          }
      }
    })
  }

  def submit(action: F[Unit]): F[Unit] = offer(Run(action))

  /** A FIFO barrier for actions admitted before this barrier; not an exporter flush. */
  def flush: F[Unit] =
    Deferred[F, Either[Throwable, Unit]].flatMap { ack =>
      offer(Barrier(ack)) *> F.race(stopped.get, ack.get).flatMap {
        case Left(error)   => F.raiseError[Unit](error)
        case Right(result) => result.liftTo[F]
      }
    }

  private def worker: F[Unit] = {
    def loop: F[Unit] = queue.take.flatMap {
      case Run(action) =>
        failed.get.flatMap {
          case Some(_) => F.unit
          case None =>
            action.attempt.flatMap {
              case Right(_)    => F.unit
              case Left(error) => failed.set(Some(error)) *> stopped.complete(error).void
            }
        } *> F.defer(loop)
      case Barrier(ack) =>
        failed.get.flatMap(error => ack.complete(error.toLeft(())).void) *> F.defer(loop)
      case Stop() => F.unit
    }
    loop.guaranteeCase {
      case Outcome.Succeeded(_) => finished.complete(()).void
      case Outcome.Errored(error) =>
        failed.set(Some(error)) *> stopped.complete(error).void *> finished.complete(()).void
      case Outcome.Canceled() =>
        val error = new WorkerCanceled
        failed.set(Some(error)) *> stopped.complete(error).void *> finished.complete(()).void
    }
  }

  private def shutdown: F[Unit] =
    stopped.complete(new Closed).void *>
      F.race(finished.get, admission.lock.surround(queue.offer(Stop[F]()))).void *>
      finished.get *>
      failed.get.flatMap(_.fold(F.unit)(F.raiseError[Unit]))
}

private[pipeline] object AsyncDeliveryQueue {
  final class Closed         extends IllegalStateException("logging queue is closed")
  final class DrainTimedOut  extends RuntimeException("logging queue shutdown timed out")
  final class WorkerCanceled extends RuntimeException("logging queue worker was canceled")

  private sealed trait Command[F[_]]
  private final case class Run[F[_]](action: F[Unit]) extends Command[F]
  private final case class Barrier[F[_]](ack: Deferred[F, Either[Throwable, Unit]])
      extends Command[F]
  private final case class Stop[F[_]]() extends Command[F]

  def resource[F[_]](
      capacity: Int,
      shutdownTimeout: FiniteDuration = 5.seconds
  )(implicit F: Async[F]): Resource[F, AsyncDeliveryQueue[F]] = {
    val allocate = for {
      _ <- F.delay {
        require(capacity > 0, "queue capacity must be positive")
        require(shutdownTimeout > Duration.Zero, "shutdown timeout must be positive")
      }
      queue    <- Queue.bounded[F, Command[F]](capacity)
      lock     <- Mutex[F]
      stopped  <- Deferred[F, Throwable]
      failed   <- Ref.of[F, Option[Throwable]](None)
      finished <- Deferred[F, Unit]
      result = new AsyncDeliveryQueue(queue, lock, stopped, failed, finished)
      fiber <- result.worker.start
    } yield (result, fiber)

    Resource
      .make(allocate) { case (queue, fiber) =>
        queue.shutdown
          .timeoutTo(shutdownTimeout, F.raiseError[Unit](new DrainTimedOut))
          .guarantee(fiber.cancel)
      }
      .map(_._1)
  }
}
