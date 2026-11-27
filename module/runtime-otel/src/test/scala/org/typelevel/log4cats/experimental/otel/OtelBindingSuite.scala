package org.typelevel.log4cats.experimental.otel

import cats.Applicative
import cats.effect.{IO, IOLocal, Ref}
import cats.mtl.LiftValue
import cats.syntax.all._
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.Scope
import org.typelevel.log4cats.experimental.pipeline.Pipeline
import org.typelevel.otel4s.oteljava.testkit.logs.LogsTestkit

final class OtelBindingSuite extends CatsEffectSuite {
  test("safe binding stays in the caller effect and each native logger is resolved only once") {
    LogsTestkit.inMemory[IO]().use { kit =>
      for {
        local   <- IOLocal("startup")
        lookups <- Ref.of[IO, Vector[String]](Vector.empty)
        lift = new LiftValue[IO, IO] {
          def applicativeF: Applicative[IO] = Applicative[IO]
          def applicativeG: Applicative[IO] = Applicative[IO]
          def apply[A](fa: IO[A]): IO[A]    = IO.cede *> local.get.flatMap(value => lookups.update(_ :+ value)) *> fa
        }
        provider = kit.loggerProvider.liftTo[IO](implicitly[cats.Monad[IO]], lift)
        _ <- Pipeline.resource(Pipeline.fromBackend[IO](OtelBackend.resource(provider))).use { factory =>
          val safe = factory.fromName("safe.component")
          for {
            before       <- lookups.get
            _            <- IO(assertEquals(before, Vector.empty[String]))
            _            <- local.set("caller")
            safeLogger   <- safe
            afterSafe    <- lookups.get
            _            <- IO(assertEquals(afterSafe, Vector("caller")))
            unsafeLogger <- IO.blocking(factory.getLoggerFromName("unsafe.component"))
            afterUnsafe  <- lookups.get
            _            <- IO(assertEquals(afterUnsafe.size, 2))
            _            <- safeLogger.info("one") *> safeLogger.info("two") *> unsafeLogger.info("three")
            records      <- kit.finishedLogs
          } yield assertEquals(
            records.map(_.getInstrumentationScopeInfo.getName).toVector,
            Vector("safe.component", "safe.component", "unsafe.component")
          )
        }
      } yield ()
    }
  }

  test("backend Resource release shuts down the unsafe binding dispatcher") {
    LogsTestkit.inMemory[IO]().use { kit =>
      for {
        allocated <- OtelBackend.resource(kit.loggerProvider).allocated
        (backend, close) = allocated
        _     <- IO.blocking(backend.bindUnsafe(Scope("before-close"))).guarantee(close)
        after <- IO.blocking(backend.bindUnsafe(Scope("after-close"))).attempt
      } yield assert(after.swap.exists(_.isInstanceOf[IllegalStateException]))
    }
  }
}
