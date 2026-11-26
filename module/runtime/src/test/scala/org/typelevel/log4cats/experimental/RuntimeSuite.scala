package org.typelevel.log4cats.experimental

import cats.effect.{Deferred, IO}
import cats.syntax.all._
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental.backend.{EncodedEvent, EventMeta}
import org.typelevel.log4cats.experimental.console.ConsoleRenderer
import org.typelevel.log4cats.experimental.context.ScopedFields
import org.typelevel.log4cats.experimental.encoding.ValueEncoding
import org.typelevel.log4cats.experimental.value.Value

final class RuntimeSuite extends CatsEffectSuite {
  test("nested IOLocal scopes restore the immediate parent after success and failure") {
    for {
      scoped <- ScopedFields.create
      _ <- scoped.scope(Fields(Field("parent", "outer"))) {
        for {
          _            <- scoped.scope(Fields(Field("parent", "inner")))(IO.unit)
          afterSuccess <- scoped.current
          _ <- scoped
            .scope(Fields(Field("parent", "inner")))(IO.raiseError[Unit](new RuntimeException("expected")))
            .attempt
          afterError <- scoped.current
        } yield {
          val expected: Value = Value.Obj(Map("parent" -> Value.Text("outer")))
          assertEquals(afterSuccess.encode(ValueEncoding.value), expected)
          assertEquals(afterError.encode(ValueEncoding.value), expected)
        }
      }
      after <- scoped.current
    } yield assert(after.isEmpty)
  }

  test("IOLocal child inheritance, sibling isolation, and cancellation restoration") {
    for {
      scoped          <- ScopedFields.create
      entered         <- Deferred[IO, Unit]
      childContext    <- Deferred[IO, Fields]
      siblingContext  <- Deferred[IO, Fields]
      outsideOnCancel <- Deferred[IO, Fields]
      canceled <- scoped
        .scope(Fields(Field("x", "outer"))) {
          for {
            inherited <- scoped.current
            left <- scoped
              .scope(Fields(Field("x", "left"))) {
                scoped.current
                  .flatMap(childContext.complete)
                  .void *> entered.complete(()).void *> IO.never[Fields]
              }
              .onCancel(scoped.current.flatMap(outsideOnCancel.complete).void)
              .start
            _             <- entered.get
            sibling       <- scoped.current.flatMap(siblingContext.complete).void.start
            _             <- sibling.joinWithNever
            child         <- childContext.get
            siblingFields <- siblingContext.get
            parent        <- scoped.current
            _             <- left.cancel
            duringCancel  <- outsideOnCancel.get
            _ <- IO(
              assertEquals(
                duringCancel.encode(ValueEncoding.value),
                Fields(Field("x", "outer")).encode(ValueEncoding.value)
              )
            )
          } yield (inherited, child, siblingFields, parent)
        }
        .attempt
      after <- scoped.current
    } yield {
      assert(canceled.isRight)
      assertEquals(
        canceled.toOption.get._1.encode(ValueEncoding.value),
        Fields(Field("x", "outer")).encode(ValueEncoding.value)
      )
      assertEquals(
        canceled.toOption.get._2.encode(ValueEncoding.value),
        Fields(Field("x", "left")).encode(ValueEncoding.value)
      )
      assertEquals(
        canceled.toOption.get._3.encode(ValueEncoding.value),
        Fields(Field("x", "outer")).encode(ValueEncoding.value)
      )
      assertEquals(
        canceled.toOption.get._4.encode(ValueEncoding.value),
        Fields(Field("x", "outer")).encode(ValueEncoding.value)
      )
      assert(after.isEmpty)
    }
  }

  test("renderer sorts keys, escapes controls, and includes stack frames") {
    val ex = new IllegalStateException("bad\nvalue")
    val event: EncodedEvent[Value] = EncodedEvent(
      EventMeta(Level.Error, Scope("z.scope"), Some("event"), 7L),
      Value.Obj(Map("quote\"" -> Value.Text("line\n"))),
      Value.Obj(Map("z" -> Value.Int64(2), "a" -> Value.Bool(true))),
      Some(ex),
      org.typelevel.vault.Vault.empty
    )
    val rendered = ConsoleRenderer.render(event)
    assert(rendered.indexOf("\"a\"") < rendered.indexOf("\"z\""))
    assert(rendered.contains("line\\n"))
    assert(rendered.contains("java.lang.IllegalStateException"))
    assert(rendered.contains("at org.typelevel.log4cats.experimental.RuntimeSuite"))
  }
}
