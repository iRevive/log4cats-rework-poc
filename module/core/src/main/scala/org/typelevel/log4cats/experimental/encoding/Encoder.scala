package org.typelevel.log4cats.experimental.encoding

import scala.collection.immutable

trait Encoder[-A] {
  def encode[V](value: A)(target: ValueEncoding[V]): V
  final def contramap[B](f: B => A): Encoder[B] = new Encoder[B] {
    def encode[V](value: B)(target: ValueEncoding[V]): V = Encoder.this.encode(f(value))(target)
  }
}

object Encoder {
  def apply[A](implicit encoder: Encoder[A]): Encoder[A] = encoder

  implicit val stringEncoder: Encoder[String] = new Encoder[String] {
    def encode[V](value: String)(out: ValueEncoding[V]): V = out.text(value)
  }
  implicit val booleanEncoder: Encoder[Boolean] = new Encoder[Boolean] {
    def encode[V](value: Boolean)(out: ValueEncoding[V]): V = out.boolean(value)
  }
  implicit val intEncoder: Encoder[Int] = new Encoder[Int] {
    def encode[V](value: Int)(out: ValueEncoding[V]): V = out.int64(value.toLong)
  }
  implicit val longEncoder: Encoder[Long] = new Encoder[Long] {
    def encode[V](value: Long)(out: ValueEncoding[V]): V = out.int64(value)
  }
  implicit val floatEncoder: Encoder[Float] = new Encoder[Float] {
    def encode[V](value: Float)(out: ValueEncoding[V]): V = out.float64(value.toDouble)
  }
  implicit val doubleEncoder: Encoder[Double] = new Encoder[Double] {
    def encode[V](value: Double)(out: ValueEncoding[V]): V = out.float64(value)
  }
  implicit val bytesEncoder: Encoder[Array[Byte]] = new Encoder[Array[Byte]] {
    def encode[V](value: Array[Byte])(out: ValueEncoding[V]): V = out.bytes(value)
  }
  implicit def optionEncoder[A](implicit e: Encoder[A]): Encoder[Option[A]] =
    new Encoder[Option[A]] {
      def encode[V](value: Option[A])(out: ValueEncoding[V]): V =
        value.fold(out.empty)(e.encode(_)(out))
    }
  implicit def listEncoder[A](implicit e: Encoder[A]): Encoder[List[A]] = new Encoder[List[A]] {
    def encode[V](value: List[A])(out: ValueEncoding[V]): V = {
      val b = out.array()
      value.foreach(a => b.add(e.encode(a)(out)))
      b.result()
    }
  }
  implicit def vectorEncoder[A](implicit e: Encoder[A]): Encoder[Vector[A]] =
    new Encoder[Vector[A]] {
      def encode[V](value: Vector[A])(out: ValueEncoding[V]): V = {
        val b = out.array()
        value.foreach(a => b.add(e.encode(a)(out)))
        b.result()
      }
    }
  implicit def mapEncoder[A](implicit e: Encoder[A]): Encoder[immutable.Map[String, A]] =
    new Encoder[immutable.Map[String, A]] {
      def encode[V](value: immutable.Map[String, A])(out: ValueEncoding[V]): V = {
        val b = out.obj()
        value.foreach { case (k, v) => b.put(k, e.encode(v)(out)) }
        b.result()
      }
    }
}
