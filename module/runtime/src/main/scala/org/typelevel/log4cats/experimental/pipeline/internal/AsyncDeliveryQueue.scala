package org.typelevel.log4cats.experimental.pipeline.internal

import cats.effect.std.{Console, Mutex, Queue}
import org.typelevel.log4cats.experimental.pipeline.DeliveryFailurePolicy
import cats.effect.syntax.all._
import cats.effect.{Async, Deferred, Outcome, Ref, Resource}
import cats.syntax.all._
import scala.concurrent.duration._

/**
  * A bounded queue that reports failed deliveries and continues. Worker termination and shutdown
  * remain terminal. Successful submit acknowledges admission only.
  */
private[pipeline] final class AsyncDeliveryQueue[F[_]] private (
    queue: Queue[F, AsyncDeliveryQueue.Command[F]],
    admission: Mutex[F],
    stopped: Deferred[F, Throwable],
    terminalError: Ref[F, Option[Throwable]],
    finished: Deferred[F, Unit]
)(implicit F: Async[F], C: Console[F]) {
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
    Deferred[F, Unit].flatMap { ack =>
      offer(Barrier(ack)) *> F.race(stopped.get, ack.get).flatMap {
        case Left(error) => F.raiseError[Unit](error)
        case Right(_)    => F.unit
      }
    }

  private def worker: F[Unit] = {
    def loop: F[Unit] = queue.take.flatMap {
      case Run(action) =>
        action.handleErrorWith(error =>
          DeliveryFailurePolicy.reportToConsole[F]("log4cats: queued delivery failed", error)
        ) *> F.defer(loop)
      case Barrier(ack) => ack.complete(()).void *> F.defer(loop)
      case Stop()       => F.unit
    }
    loop.guaranteeCase {
      case Outcome.Succeeded(_) => finished.complete(()).void
      case Outcome.Errored(error) =>
        terminalError.set(Some(error)) *> stopped.complete(error).void *> finished.complete(()).void
      case Outcome.Canceled() =>
        val error = new WorkerCanceled
        terminalError.set(Some(error)) *> stopped.complete(error).void *> finished.complete(()).void
    }
  }

  private def shutdown: F[Unit] =
    stopped.complete(new Closed).void *>
      F.race(finished.get, admission.lock.surround(queue.offer(Stop[F]()))).void *>
      finished.get *>
      terminalError.get.flatMap(_.fold(F.unit)(F.raiseError[Unit]))
}

private[pipeline] object AsyncDeliveryQueue {
  final class Closed         extends IllegalStateException("logging queue is closed")
  final class DrainTimedOut  extends RuntimeException("logging queue shutdown timed out")
  final class WorkerCanceled extends RuntimeException("logging queue worker was canceled")

  private sealed trait Command[F[_]]
  private final case class Run[F[_]](action: F[Unit]) extends Command[F]
  private final case class Barrier[F[_]](ack: Deferred[F, Unit])
      extends Command[F]
  private final case class Stop[F[_]]() extends Command[F]

  def resource[F[_]](
      capacity: Int,
      shutdownTimeout: FiniteDuration = 5.seconds
  )(implicit F: Async[F], C: Console[F]): Resource[F, AsyncDeliveryQueue[F]] = {
    val allocate = for {
      _ <- F.delay {
        require(capacity > 0, "queue capacity must be positive")
        require(shutdownTimeout > Duration.Zero, "shutdown timeout must be positive")
      }
      queue         <- Queue.bounded[F, Command[F]](capacity)
      lock          <- Mutex[F]
      stopped       <- Deferred[F, Throwable]
      terminalError <- Ref.of[F, Option[Throwable]](None)
      finished      <- Deferred[F, Unit]
      result = new AsyncDeliveryQueue(queue, lock, stopped, terminalError, finished)
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
