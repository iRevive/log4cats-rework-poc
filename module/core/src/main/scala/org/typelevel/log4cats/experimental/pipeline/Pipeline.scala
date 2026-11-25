package org.typelevel.log4cats.experimental.pipeline

import cats.effect.{Async, Clock, Resource, Sync}
import cats.syntax.all._
import org.typelevel.log4cats.experimental.backend.{Backend, BoundBackend, CapturedEvent, Delivery, EventMeta}
import org.typelevel.log4cats.experimental.context.ContextSource
import org.typelevel.log4cats.experimental.pipeline.internal.{
  AccumulatingRecordWriter,
  AsyncDeliveryQueue,
  RecordPreparer
}
import org.typelevel.log4cats.experimental.{Fields, Level, LoggerFactory, RecordWriter, Scope}
import org.typelevel.vault.Vault
import scala.concurrent.duration._

final class Pipeline[F[_]] private[pipeline] (private[pipeline] val node: Pipeline.Node[F]) {
  def ++(other: Pipeline[F]): Pipeline[F] = Pipeline(new Pipeline.Concat(node, other.node))

  def atLeast(level: Level): Pipeline[F] = Pipeline(new Pipeline.Minimum(level, node))

  def withFields(fields: Fields): Pipeline[F] = node match {
    case current: Pipeline.Static[F] =>
      Pipeline(new Pipeline.Static(current.fields ++ fields, current.child))
    case _ => Pipeline(new Pipeline.Static(fields, node))
  }

  def withContextSource(source: ContextSource[F]): Pipeline[F] =
    Pipeline(new Pipeline.Source(source, node))

  def filterMetadata(predicate: (Level, Option[String]) => Boolean): Pipeline[F] =
    Pipeline(new Pipeline.MetadataFilter(predicate, node))

  def filterFields(predicate: Fields => Boolean): Pipeline[F] =
    Pipeline(new Pipeline.FieldsFilter(predicate, node))

  def async(capacity: Int, shutdownTimeout: FiniteDuration = 5.seconds)(implicit
      F: Async[F]
  ): Pipeline[F] = {
    require(capacity > 0, "async queue capacity must be positive")
    require(shutdownTimeout > Duration.Zero, "shutdown timeout must be positive")
    Pipeline(
      new Pipeline.AsyncNode(
        node,
        AsyncDeliveryQueue.resource[F](capacity, shutdownTimeout)
      )
    )
  }
}

object Pipeline {
  private[pipeline] sealed trait Node[F[_]]
  private[pipeline] final class OutputNode[F[_]](val acquire: () => Resource[F, Backend[F]])
      extends Node[F]
  private[pipeline] final class Concat[F[_]](val left: Node[F], val right: Node[F])  extends Node[F]
  private[pipeline] final class Minimum[F[_]](val level: Level, val child: Node[F])  extends Node[F]
  private[pipeline] final class Static[F[_]](val fields: Fields, val child: Node[F]) extends Node[F]
  private[pipeline] final class Source[F[_]](val source: ContextSource[F], val child: Node[F])
      extends Node[F]
  private[pipeline] final class MetadataFilter[F[_]](
      val predicate: (Level, Option[String]) => Boolean,
      val child: Node[F]
  ) extends Node[F]
  private[pipeline] final class FieldsFilter[F[_]](
      val predicate: Fields => Boolean,
      val child: Node[F]
  ) extends Node[F]
  private[pipeline] final class AsyncNode[F[_]](
      val child: Node[F],
      val queue: Resource[F, AsyncDeliveryQueue[F]]
  ) extends Node[F]

  def fromBackend[F[_]](acquire: => Resource[F, Backend[F]]): Pipeline[F] =
    new Pipeline(new OutputNode(() => acquire))

  private[pipeline] def apply[F[_]](node: Node[F]): Pipeline[F] = new Pipeline(node)

  def resource[F[_]](pipeline: Pipeline[F], rootContext: ContextSource[F])(implicit
      F: Sync[F],
      clock: Clock[F]
  ): Resource[F, LoggerFactory[F]] =
    runtimeResource(pipeline, rootContext)(F, clock).map(runtime => runtime: LoggerFactory[F])

  def resource[F[_]](pipeline: Pipeline[F])(implicit
      F: Sync[F],
      clock: Clock[F]
  ): Resource[F, LoggerFactory[F]] = resource(pipeline, ContextSource.empty[F])(F, clock)

  def runtimeResource[F[_]](pipeline: Pipeline[F], rootContext: ContextSource[F])(implicit
      F: Sync[F],
      clock: Clock[F]
  ): Resource[F, LoggingRuntime[F]] =
    compile(pipeline.node, Config.empty[F], 0).map { compiled =>
      new LoggingRuntime[F](
        scope =>
          compiled.outputs
            .traverse { output =>
              output.backend.bind(scope).map(backend => Output[F, BoundBackend[F]](backend, output.config))
            }
            .map(outputs => bound(compiled.routing, outputs, rootContext, clock, scope)),
        scope => {
          val outputs = compiled.outputs.map { output =>
            Output[F, BoundBackend[F]](output.backend.bindUnsafe(scope), output.config)
          }
          bound(compiled.routing, outputs, rootContext, clock, scope)
        },
        flush(compiled.routing)
      )
    }

  def runtimeResource[F[_]](pipeline: Pipeline[F])(implicit
      F: Sync[F],
      clock: Clock[F]
  ): Resource[F, LoggingRuntime[F]] = runtimeResource(pipeline, ContextSource.empty[F])(F, clock)

  private final class SourceCapture[F[_]](val source: ContextSource[F])

  private final case class Config[F[_]](
      minimum: Level,
      fields: Fields,
      sources: Vector[SourceCapture[F]],
      metadataFilters: Vector[(Level, Option[String]) => Boolean],
      fieldFilters: Vector[Fields => Boolean]
  )
  private object Config {
    def empty[F[_]]: Config[F] = Config(Level.Trace, Fields.empty, Vector.empty, Vector.empty, Vector.empty)
  }

  private final case class Output[F[_], B](backend: B, config: Config[F])
  private final case class Compiled[F[_]](outputs: Vector[Output[F, Backend[F]]], routing: Routing[F])

  private sealed trait Routing[F[_]]
  private final case class Direct[F[_]](index: Int)                            extends Routing[F]
  private final case class Sequence[F[_]](left: Routing[F], right: Routing[F]) extends Routing[F]
  private final case class Queued[F[_]](child: Routing[F], queue: AsyncDeliveryQueue[F], indices: Range)
      extends Routing[F]

  private def compile[F[_]](node: Node[F], config: Config[F], offset: Int)(implicit
      F: Sync[F]
  ): Resource[F, Compiled[F]] =
    Resource.eval(F.unit).flatMap { _ =>
      node match {
        case n: OutputNode[F] =>
          Resource.eval(F.delay(n.acquire())).flatMap(identity).map { backend =>
            Compiled(Vector(Output[F, Backend[F]](backend, config)), Direct[F](offset))
          }
        case n: Concat[F] =>
          compile(n.left, config, offset).flatMap { left =>
            compile(n.right, config, offset + left.outputs.size).map { right =>
              Compiled(left.outputs ++ right.outputs, Sequence(left.routing, right.routing))
            }
          }
        case n: Minimum[F] =>
          val minimum = if (n.level.rank > config.minimum.rank) n.level else config.minimum
          compile(n.child, config.copy(minimum = minimum), offset)
        case n: Static[F] =>
          compile(n.child, config.copy(fields = n.fields ++ config.fields), offset)
        case n: Source[F] =>
          compile(n.child, config.copy(sources = config.sources :+ new SourceCapture(n.source)), offset)
        case n: MetadataFilter[F] =>
          compile(n.child, config.copy(metadataFilters = n.predicate +: config.metadataFilters), offset)
        case n: FieldsFilter[F] =>
          compile(n.child, config.copy(fieldFilters = n.predicate +: config.fieldFilters), offset)
        case n: AsyncNode[F] =>
          compile(n.child, config, offset).flatMap { child =>
            n.queue.map(queue =>
              child.copy(routing = Queued(child.routing, queue, offset until offset + child.outputs.size))
            )
          }
      }
    }

  private def bound[F[_]: Sync](
      routing: Routing[F],
      outputs: Vector[Output[F, BoundBackend[F]]],
      rootContext: ContextSource[F],
      clock: Clock[F],
      scope: Scope
  ): RecordPreparer[F] = new RecordPreparer[F] {
    def prepare(level: Level, eventName: Option[String])(write: RecordWriter => Unit): F[Delivery[F]] =
      Pipeline.prepare(routing, outputs, rootContext, clock, scope, level, eventName)(write)
  }

  private def prepare[F[_]](
      routing: Routing[F],
      outputs: Vector[Output[F, BoundBackend[F]]],
      rootContext: ContextSource[F],
      clock: Clock[F],
      scope: Scope,
      level: Level,
      eventName: Option[String]
  )(write: RecordWriter => Unit)(implicit F: Sync[F]): F[Delivery[F]] =
    F.delay {
      outputs.zipWithIndex.filter { case (output, _) =>
        output.config.minimum.rank <= level.rank && output.config.metadataFilters.forall(_(level, eventName))
      }
    }.flatMap { live =>
      if (live.isEmpty) F.pure(Delivery.noop[F])
      else {
        val sources = live.flatMap(_._1.config.sources).distinct
        for {
          timestamp <- clock.realTime.map(_.toNanos)
          root      <- rootContext.current
          sourced   <- sources.traverse(s => s.source.current.map(s -> _))
          meta = EventMeta(level, scope, eventName, timestamp)
          captures <- live.traverse { case (output, _) => output.backend.capture(meta) }
          contexts = sourced.toMap
          accepted <- live
            .traverse { case (output, index) =>
              val attachments =
                (Vector(root.attachments) ++ output.config.sources.map(s => contexts(s).attachments) ++ captures)
                  .foldLeft(Vault.empty)(_ ++ _)
              output.backend.accepts(meta, attachments).map { accepts =>
                if (accepts) Some((output, index, attachments)) else None
              }
            }
            .map(_.flatten)
          deliveries <-
            if (accepted.isEmpty) F.pure(Map.empty[Int, Delivery[F]])
            else
              F.delay(AccumulatingRecordWriter.capture(write)).flatMap { payload =>
                accepted
                  .traverse { case (output, index, attachments) =>
                    val ambient = (Vector(root.fields) ++ output.config.sources.map(s => contexts(s).fields))
                      .foldLeft(Fields.empty)(_ ++ _)
                    val fields = output.config.fields ++ ambient ++ payload.fields
                    if (!output.config.fieldFilters.forall(_(fields))) F.pure(Option.empty[(Int, Delivery[F])])
                    else {
                      val event = CapturedEvent(meta, fields, payload.body, payload.cause, attachments)
                      output.backend.prepare(event).map(delivery => Option(index -> delivery))
                    }
                  }
                  .map(_.flatten.toMap)
              }
        } yield Delivery(deliver(routing, deliveries))
      }
    }

  private def deliver[F[_]](routing: Routing[F], deliveries: Map[Int, Delivery[F]])(implicit
      F: Sync[F]
  ): F[Unit] = F.defer {
    routing match {
      case r: Direct[F]   => deliveries.get(r.index).fold(F.unit)(_.deliver)
      case r: Sequence[F] => deliver(r.left, deliveries) *> deliver(r.right, deliveries)
      case r: Queued[F] =>
        val pending = deliveries.filter { case (index, _) => r.indices.contains(index) }
        if (pending.isEmpty) F.unit else r.queue.submit(deliver(r.child, pending))
    }
  }

  private def flush[F[_]](routing: Routing[F])(implicit F: Sync[F]): F[Unit] = F.defer {
    routing match {
      case _: Direct[F]   => F.unit
      case r: Sequence[F] => flush(r.left) *> flush(r.right)
      case r: Queued[F]   => r.queue.flush *> flush(r.child)
    }
  }
}
