package org.typelevel.log4cats.experimental.pipeline

import cats.effect.Sync
import cats.effect.std.Console
import cats.syntax.all._
import org.typelevel.log4cats.experimental.{Level, Scope}

/** Applies only to pipeline delivery. Capture, enablement and encoding remain strict. */
sealed abstract class DeliveryFailurePolicy[F[_]] {
  protected def onFailure(failure: DeliveryFailure)(implicit F: Sync[F], C: Console[F]): F[Unit]

  private[pipeline] final def deliver(action: F[Unit], level: Level, scope: Scope, eventName: Option[String])(implicit
      F: Sync[F],
      C: Console[F]
  ): F[Unit] =
    action.handleErrorWith(error => onFailure(DeliveryFailure(level, scope, eventName, error)))
}

object DeliveryFailurePolicy {

  /** Report directly through Console's stderr, then continue without retrying. */
  def stderr[F[_]]: DeliveryFailurePolicy[F] = new DeliveryFailurePolicy[F] {
    protected def onFailure(failure: DeliveryFailure)(implicit F: Sync[F], C: Console[F]): F[Unit] =
      fallback(failure, None)
  }

  /**
    * Report once and continue. Reporter errors fall back to stderr; they do not invoke the reporter
    * again. The reporter must not log through the same failing pipeline and should finish promptly.
    * Cancellation of delivery or reporting is not recovered.
    */
  def reportAndContinue[F[_]](report: DeliveryFailure => F[Unit]): DeliveryFailurePolicy[F] =
    new DeliveryFailurePolicy[F] {
      protected def onFailure(failure: DeliveryFailure)(implicit F: Sync[F], C: Console[F]): F[Unit] =
        F.defer(report(failure)).handleErrorWith(error => fallback(failure, Some(error)))
    }

  private def fallback[F[_]](failure: DeliveryFailure, reporterError: Option[Throwable])(implicit
      F: Sync[F],
      C: Console[F]
  ): F[Unit] = reportToConsole(
    "log4cats: delivery failed for " + failure.scope.name + " at " + failure.level +
      failure.eventName.fold("")(name => " (" + name + ")"),
    failure.error,
    reporterError
  )

  private[pipeline] def reportToConsole[F[_]](
      message: String,
      error: Throwable,
      reporterError: Option[Throwable] = None
  )(implicit
      F: Sync[F],
      C: Console[F]
  ): F[Unit] =
    F.defer {
      C.errorln(message) *> C.printStackTrace(error) *>
        reporterError.traverse_ { error =>
          C.errorln("log4cats: delivery error reporter also failed") *> C.printStackTrace(error)
        }
    }.handleErrorWith(_ => F.unit)
}
