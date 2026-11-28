package org.typelevel.log4cats.experimental.internal

import cats.~>
import cats.data.Kleisli
import cats.effect.IO
import cats.syntax.all._
import munit.CatsEffectSuite
import org.typelevel.log4cats.experimental._
import org.typelevel.log4cats.experimental.syntax._
import org.typelevel.log4cats.experimental.encoding.{Encoder, ValueEncoding}
import org.typelevel.log4cats.experimental.value.Value

final class LoggerEnrichmentSuite extends CatsEffectSuite {
  test("later defaults and call values win independently; metadata, body and cause survive") {
    var records = Vector.empty[(Level, Option[String], RecordData)]
    val base: Logger[IO] = (level: Level, name: Option[String], write: RecordWriter => Unit) =>
      IO(records :+= ((level, name, AccumulatingRecordWriter.capture(write))))
    val logger = base
      .withFields(Fields(Field("id", 1), Field("first", true)))
      .withMdc(Mdc("id" -> "first", "only.mdc" -> "retained"))
      .withFields(Fields(Field("id", 2), Field("second", true)))
      .withMdc(Mdc("id" -> "second"))
    val failure = new RuntimeException("event cause")
    val call = logger.warnEvent("checkout")(
      Event.message("body").withField("id", 3).withMdc("id", "call").withCause(failure)
    )
    for {
      _ <- logger.info("defaults")
      _ <- call
      _ <- call
    } yield {
      assertEquals(records.map(_._1), Vector(Level.Info, Level.Warn, Level.Warn))
      assertEquals(records.map(_._2), Vector(None, Some("checkout"), Some("checkout")))
      val defaults = records.head._3
      assertEquals(defaults.mdc.values, Map("id" -> "second", "only.mdc" -> "retained"))
      def fields(id: Long): Value =
        Value.Obj(Map("id" -> Value.Int64(id), "first" -> Value.Bool(true), "second" -> Value.Bool(true)))
      assertEquals(defaults.fields.encode(ValueEncoding.value), fields(2))
      records.tail.foreach { case (_, _, record) =>
        assertEquals(record.fields.encode(ValueEncoding.value), fields(3))
        assertEquals(record.mdc.values, Map("id" -> "call", "only.mdc" -> "retained"))
        assertEquals(record.body.encode(ValueEncoding.value), Value.Text("body"))
        assertEquals(record.cause, Some(failure))
      }
    }
  }

  test("rejected records skip callbacks and encoding; defaults encode on each accepted execution") {
    var encodes   = 0
    var callbacks = 0
    var records   = Vector.empty[Value]
    val encoder = new Encoder[Int] {
      def encode[V](value: Int)(out: ValueEncoding[V]): V = { encodes += 1; out.int64(value.toLong) }
    }
    val base: Logger[IO] = (level: Level, _: Option[String], write: RecordWriter => Unit) =>
      IO {
        if (level != Level.Debug)
          records :+= AccumulatingRecordWriter.capture(write).fields.encode(ValueEncoding.value)
      }
    val logger   = base.withFields(Fields(Field("static", 42)(encoder))).withMdc(Mdc("id" -> "static"))
    val rejected = logger.debug { callbacks += 1; "rejected" }
    val accepted = logger.info { callbacks += 1; "accepted" }
    assertEquals(encodes, 0)
    assertEquals(callbacks, 0)
    for {
      _ <- rejected
      _ <- IO { assertEquals(encodes, 0); assertEquals(callbacks, 0) }
      _ <- accepted
      _ <- accepted
    } yield {
      assertEquals(callbacks, 2)
      assertEquals(encodes, 2)
      assertEquals(records, Vector.fill(2)(Value.Obj(Map("static" -> Value.Int64(42)))))
    }
  }

  test("mapK and arbitrary wrappers preserve callback-based enrichment precedence") {
    type Reader[A] = Kleisli[IO, Unit, A]
    val lift = new (IO ~> Reader) {
      def apply[A](fa: IO[A]): Reader[A] = Kleisli.liftF(fa)
    }
    val lower = new (Reader ~> IO) {
      def apply[A](fa: Reader[A]): IO[A] = fa.run(())
    }
    var records = Vector.empty[RecordData]
    val base: Logger[IO] = (_: Level, _: Option[String], write: RecordWriter => Unit) =>
      IO(records :+= AccumulatingRecordWriter.capture(write))
    def first[F[_]](logger: Logger[F]): Logger[F] =
      logger.withFields(Fields(Field("id", 1))).withMdc(Mdc("id" -> "first"))
    def second[F[_]](logger: Logger[F]): Logger[F] =
      logger.withFields(Fields(Field("id", 2))).withMdc(Mdc("id" -> "second"))
    def wrap[F[_]](logger: Logger[F]): Logger[F] = new Logger[F] {
      def log(level: Level, name: Option[String], write: RecordWriter => Unit): F[Unit] =
        logger.log(
          level,
          name,
          writer => {
            writer.field("wrapped", true)
            write(writer)
          }
        )
    }
    val variants = List(
      wrap(second(first(base))).mapK(lift),
      second(wrap(first(base)).mapK(lift)),
      second(first(wrap(base.mapK(lift))))
    )
    variants
      .traverse_ { logger =>
        val translated = logger.mapK(lower)
        translated.info("defaults") *>
          translated.infoEvent(Event.message("call").withField("id", 3).withMdc("id", "call"))
      }
      .map { _ =>
        assertEquals(records.size, 6)
        records.grouped(2).foreach { pair =>
          assertEquals(
            pair.head.fields.encode(ValueEncoding.value),
            Value.Obj(Map("id" -> Value.Int64(2), "wrapped" -> Value.Bool(true)))
          )
          assertEquals(pair.head.mdc.values, Map("id" -> "second"))
          assertEquals(
            pair.last.fields.encode(ValueEncoding.value),
            Value.Obj(Map("id" -> Value.Int64(3), "wrapped" -> Value.Bool(true)))
          )
          assertEquals(pair.last.mdc.values, Map("id" -> "call"))
        }
      }
  }
}
