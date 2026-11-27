package org.typelevel.log4cats.experimental.pipeline

import org.typelevel.log4cats.experimental.{Level, Scope}

import cats.effect.{IO, Outcome, Ref}
import cats.syntax.all._
import munit.CatsEffectSuite

final class DeliveryFailurePolicySuite extends CatsEffectSuite {
  private val scope = Scope("delivery")

  test("report once per failed execution, preserve metadata, and leave success untouched") {
    val failure = new RuntimeException("sink")
    for {
      failures <- Ref.of[IO, Vector[DeliveryFailure]](Vector.empty)
      policy = DeliveryFailurePolicy.reportAndContinue[IO](e => failures.update(_ :+ e))
      effect = policy.deliver(IO.raiseError(failure), Level.Warn, scope, Some("event"))
      before <- failures.get
      _      <- effect *> effect
      _      <- policy.deliver(IO.unit, Level.Info, scope, None)
      after  <- failures.get
    } yield {
      assert(before.isEmpty)
      assertEquals(after, Vector.fill(2)(DeliveryFailure(Level.Warn, scope, Some("event"), failure)))
    }
  }

  test("synchronous and effectful reporter failures use fallback once without recursion") {
    var fallbackCalls = 0
    var reports       = 0
    val original = new RuntimeException("sink") {
      override def printStackTrace(): Unit = fallbackCalls += 1
    }
    val reporting = new RuntimeException("reporter") {
      override def printStackTrace(): Unit = ()
    }
    val synchronous = DeliveryFailurePolicy.reportAndContinue[IO] { _ => reports += 1; throw reporting }
    val effectful   = DeliveryFailurePolicy.reportAndContinue[IO](_ => IO(reports += 1) *> IO.raiseError(reporting))
    for {
      _ <- synchronous.deliver(IO.raiseError(original), Level.Info, scope, None)
      _ <- effectful.deliver(IO.raiseError(original), Level.Info, scope, None)
    } yield {
      assertEquals(reports, 2)
      assertEquals(fallbackCalls, 2)
    }
  }

  test("a broken stderr fallback cannot fail the logging effect") {
    val error = new RuntimeException("broken diagnostic") {
      override def printStackTrace(): Unit = throw new RuntimeException("stderr")
    }
    DeliveryFailurePolicy.stderr[IO].deliver(IO.raiseError(error), Level.Info, scope, None)
  }

  test("cancellation during delivery is not reported or recovered") {
    for {
      reports <- Ref.of[IO, Int](0)
      policy = DeliveryFailurePolicy.reportAndContinue[IO](_ => reports.update(_ + 1))
      fiber   <- policy.deliver(IO.canceled, Level.Info, scope, None).start
      outcome <- fiber.join
      count   <- reports.get
    } yield {
      assert(outcome.isInstanceOf[Outcome.Canceled[IO, Throwable, Unit]])
      assertEquals(count, 0)
    }
  }

  test("reporter cancellation remains cancellation") {
    val policy = DeliveryFailurePolicy.reportAndContinue[IO](_ => IO.canceled)
    for {
      fiber   <- policy.deliver(IO.raiseError(new RuntimeException("sink")), Level.Info, scope, None).start
      outcome <- fiber.join
    } yield assert(outcome match { case Outcome.Canceled() => true; case _ => false })
  }

  test("a failed diagnostic Console does not turn a sink error into a caller failure") {
    import cats.~>
    import cats.effect.Sync
    import cats.effect.std.Console
    val broken = Console[IO].mapK(new (IO ~> IO) {
      def apply[A](fa: IO[A]): IO[A] = IO.raiseError(new RuntimeException("console unavailable"))
    })
    DeliveryFailurePolicy
      .stderr[IO]
      .deliver(IO.raiseError(new RuntimeException("sink")), Level.Info, scope, None)(Sync[IO], broken)
  }
}
