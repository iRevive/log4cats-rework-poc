package consumer

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.Scope
import org.typelevel.log4cats.experimental.backend.{Backend, BoundBackend, EncodedEvent, EventMeta}
import org.typelevel.log4cats.experimental.encoding.ValueEncoding
import org.typelevel.log4cats.experimental.pipeline.Pipeline
import org.typelevel.log4cats.experimental.value.Value
import org.typelevel.vault.Vault

final class BackendBindingSuite extends CatsEffectSuite {
  test("bound handles cannot be rebound or passed as backend factories") {
    val rebound = compileErrors("""
      import cats.effect.IO
      import org.typelevel.log4cats.experimental.Scope
      import org.typelevel.log4cats.experimental.encoding.ValueEncoding
      import org.typelevel.log4cats.experimental.backend.{Backend, BoundBackend, EncodedEvent, EventMeta}
      import org.typelevel.log4cats.experimental.value.Value
      val handle: BoundBackend[IO] = null
      handle.bindUnsafe(Scope("another-name"))
    """)
    val factory = compileErrors("""
      import cats.effect.IO
      import org.typelevel.log4cats.experimental.Scope
      import org.typelevel.log4cats.experimental.encoding.ValueEncoding
      import org.typelevel.log4cats.experimental.backend.{Backend, BoundBackend, EncodedEvent, EventMeta}
      import org.typelevel.log4cats.experimental.value.Value
      val handle: BoundBackend[IO] = null
      val backend: Backend[IO] = handle
    """)
    assert(rebound.nonEmpty)
    assert(factory.nonEmpty)
  }

  test("constant backends explicitly share one handle while retaining each occurrence's logger name") {
    for {
      events <- Ref.of[IO, Vector[EncodedEvent[Value]]](Vector.empty)
      handle = new BoundBackend[IO] {
        type Repr = Value
        val encoding: ValueEncoding[Value]                            = ValueEncoding.value
        def capture(meta: EventMeta): IO[Vault]                       = IO.pure(Vault.empty)
        def accepts(meta: EventMeta, attachments: Vault): IO[Boolean] = IO.pure(true)
        def emit(event: EncodedEvent[Value]): IO[Unit]                = events.update(_ :+ event)
      }
      backend = Backend.constant[IO](handle)
      safe <- backend.bind(Scope("first"))
      unsafe = backend.bindUnsafe(Scope("second"))
      _ <- IO {
        assert(safe eq handle)
        assert(unsafe eq handle)
      }
      _ <- Pipeline.resource(Pipeline.fromBackend[IO](Resource.pure[IO, Backend[IO]](backend))).use { factory =>
        for {
          first <- factory.fromName("first")
          second = factory.fromNameUnsafe("second")
          _ <- first.info("one") *> second.info("two")
        } yield ()
      }
      recorded <- events.get
    } yield {
      assertEquals(recorded.map(_.meta.scope.name), Vector("first", "second"))
      assertEquals(recorded.map(_.body), Vector[Value](Value.Text("one"), Value.Text("two")))
    }
  }
}
