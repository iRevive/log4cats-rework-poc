package org.typelevel.log4cats.experimental.pipeline

import cats.effect.{Async, Clock, Resource, Sync}
import cats.syntax.all._
import cats.effect.std.Console
import org.typelevel.log4cats.experimental.backend.{Backend, BoundBackend, CapturedEvent, Delivery, EventMeta}
import org.typelevel.log4cats.experimental.context.{ContextSource, LogContext}
import org.typelevel.log4cats.experimental.pipeline.internal.{AsyncDeliveryQueue, RecordPreparer}
import org.typelevel.log4cats.experimental.internal.AccumulatingRecordWriter
import org.typelevel.log4cats.experimental.{Fields, Level, LoggerFactory, Mdc, RecordWriter, Scope}
import scala.concurrent.duration._

final class Pipeline[F[_]] private[pipeline] (private[pipeline] val node: Pipeline.Node[F]) {
  def ++(other: Pipeline[F]): Pipeline[F] = Pipeline(new Pipeline.Concat(node, other.node))

  def atLeast(level: Level): Pipeline[F] = Pipeline(new Pipeline.Minimum(level, node))

  def withFields(fields: Fields): Pipeline[F] = node match {
    case current: Pipeline.Static[F] =>
      Pipeline(new Pipeline.Static(current.fields ++ fields, current.child))
    case _ => Pipeline(new Pipeline.Static(fields, node))
  }

  def withMdc(mdc: Mdc): Pipeline[F] =
    node match {
      case current: Pipeline.StaticMdc[F] => Pipeline(new Pipeline.StaticMdc(current.mdc ++ mdc, current.child))
      case _                              => Pipeline(new Pipeline.StaticMdc(mdc, node))
    }

  def withContextSource(source: ContextSource[F]): Pipeline[F] =
    Pipeline(new Pipeline.Source(source, node))

  def filterMetadata(predicate: (Level, Option[String]) => Boolean): Pipeline[F] =
    Pipeline(new Pipeline.MetadataFilter(predicate, node))

  def filterFields(predicate: Fields => Boolean): Pipeline[F] =
    Pipeline(new Pipeline.FieldsFilter(predicate, node))

  /** Runs after record capture, before backend encoding; channels remain independent. */
  def filterContext(predicate: LogContext => Boolean): Pipeline[F] =
    Pipeline(new Pipeline.ContextFilter(predicate, node))

  /** Branch policies override enclosing policies; delivery recovery does not cover preparation. */
  def withDeliveryFailurePolicy(policy: DeliveryFailurePolicy[F]): Pipeline[F] =
    node match {
      case current: Pipeline.FailurePolicy[F] => Pipeline(new Pipeline.FailurePolicy(policy, current.child))
      case _                                  => Pipeline(new Pipeline.FailurePolicy(policy, node))
    }

  def async(capacity: Int, shutdownTimeout: FiniteDuration = 5.seconds)(implicit
      F: Async[F]
  ): Pipeline[F] = {
    require(capacity > 0, "async queue capacity must be positive")
    require(shutdownTimeout > Duration.Zero, "shutdown timeout must be positive")
    Pipeline(
      new Pipeline.AsyncNode(
        node,
        console => AsyncDeliveryQueue.resource[F](capacity, shutdownTimeout)(F, console)
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
  private[pipeline] final class StaticMdc[F[_]](val mdc: Mdc, val child: Node[F])    extends Node[F]
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
  private[pipeline] final class ContextFilter[F[_]](
      val predicate: LogContext => Boolean,
      val child: Node[F]
  ) extends Node[F]
  private[pipeline] final class FailurePolicy[F[_]](
      val policy: DeliveryFailurePolicy[F],
      val child: Node[F]
  ) extends Node[F]
  private[pipeline] final class AsyncNode[F[_]](
      val child: Node[F],
      val queue: Console[F] => Resource[F, AsyncDeliveryQueue[F]]
  ) extends Node[F]

  def fromBackend[F[_]](acquire: => Resource[F, Backend[F]]): Pipeline[F] =
    new Pipeline(new OutputNode(() => acquire))

  private[pipeline] def apply[F[_]](node: Node[F]): Pipeline[F] = new Pipeline(node)

  def resource[F[_]](pipeline: Pipeline[F], rootContext: ContextSource[F])(implicit
      F: Sync[F],
      clock: Clock[F],
      console: Console[F]
  ): Resource[F, LoggerFactory[F]] =
    runtimeResource(pipeline, rootContext)(F, clock, console).map(runtime => runtime: LoggerFactory[F])

  def resource[F[_]](pipeline: Pipeline[F])(implicit
      F: Sync[F],
      clock: Clock[F],
      console: Console[F]
  ): Resource[F, LoggerFactory[F]] = resource(pipeline, ContextSource.empty[F])(F, clock, console)

  def runtimeResource[F[_]](pipeline: Pipeline[F], rootContext: ContextSource[F])(implicit
      F: Sync[F],
      clock: Clock[F],
      console: Console[F]
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
      clock: Clock[F],
      console: Console[F]
  ): Resource[F, LoggingRuntime[F]] = runtimeResource(pipeline, ContextSource.empty[F])(F, clock, console)

  private final class SourceCapture[F[_]](val source: ContextSource[F])

  private final case class Config[F[_]](
      minimum: Level,
      fields: Fields,
      mdc: Mdc,
      sources: Vector[SourceCapture[F]],
      metadataFilters: Vector[(Level, Option[String]) => Boolean],
      fieldFilters: Vector[Fields => Boolean],
      contextFilters: Vector[LogContext => Boolean],
      failurePolicy: DeliveryFailurePolicy[F]
  )
  private object Config {
    def empty[F[_]]: Config[F] =
      Config(
        Level.Trace,
        Fields.empty,
        Mdc.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        DeliveryFailurePolicy.stderr[F]
      )
  }

  private final case class Output[F[_], B](backend: B, config: Config[F])
  private final case class Compiled[F[_]](outputs: Vector[Output[F, Backend[F]]], routing: Routing[F])

  private sealed trait Routing[F[_]]
  private final case class Direct[F[_]](index: Int)                            extends Routing[F]
  private final case class Sequence[F[_]](left: Routing[F], right: Routing[F]) extends Routing[F]
  private final case class Queued[F[_]](child: Routing[F], queue: AsyncDeliveryQueue[F], indices: Range)
      extends Routing[F]

  private def compile[F[_]](node: Node[F], config: Config[F], offset: Int)(implicit
      F: Sync[F],
      console: Console[F]
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
        case n: StaticMdc[F] =>
          compile(n.child, config.copy(mdc = n.mdc ++ config.mdc), offset)
        case n: Source[F] =>
          compile(n.child, config.copy(sources = config.sources :+ new SourceCapture(n.source)), offset)
        case n: MetadataFilter[F] =>
          compile(n.child, config.copy(metadataFilters = n.predicate +: config.metadataFilters), offset)
        case n: FieldsFilter[F] =>
          compile(n.child, config.copy(fieldFilters = n.predicate +: config.fieldFilters), offset)
        case n: ContextFilter[F] =>
          compile(n.child, config.copy(contextFilters = n.predicate +: config.contextFilters), offset)
        case n: FailurePolicy[F] =>
          compile(n.child, config.copy(failurePolicy = n.policy), offset)
        case n: AsyncNode[F] =>
          compile(n.child, config, offset).flatMap { child =>
            n.queue(console)
              .map(queue =>
                child.copy(routing = Queued(child.routing, queue, offset until offset + child.outputs.size))
              )
          }
      }
    }

  private def bound[F[_]: Sync: Console](
      routing: Routing[F],
      outputs: Vector[Output[F, BoundBackend[F]]],
      rootContext: ContextSource[F],
      clock: Clock[F],
      scope: Scope
  ): RecordPreparer[F] = new RecordPreparer[F] {
    def prepare(level: Level, eventName: Option[String])(write: RecordWriter => Unit): F[Delivery[F]] =
      Pipeline.prepare(routing, outputs, rootContext, clock, scope, level, eventName)(write)
  }

  private trait CapturedOutput[F[_]] {
    def data: LogContext
    def accepts(data: LogContext): F[Boolean]
    def prepare(event: CapturedEvent): F[Delivery[F]]
  }

  private def capture[F[_]](backend: BoundBackend[F], meta: EventMeta)(implicit F: Sync[F]): F[CapturedOutput[F]] =
    F.defer(backend.capture(meta)).map { context =>
      new CapturedOutput[F] {
        val data: LogContext                              = backend.contextData(context)
        def accepts(data: LogContext): F[Boolean]         = F.defer(backend.acceptsWithContext(meta, context, data))
        def prepare(event: CapturedEvent): F[Delivery[F]] = backend.prepare(event, context)
      }
    }

  private def prepare[F[_]](
      routing: Routing[F],
      outputs: Vector[Output[F, BoundBackend[F]]],
      rootContext: ContextSource[F],
      clock: Clock[F],
      scope: Scope,
      level: Level,
      eventName: Option[String]
  )(write: RecordWriter => Unit)(implicit F: Sync[F], console: Console[F]): F[Delivery[F]] =
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
          captures <- live.traverse { case (output, _) => capture(output.backend, meta) }
          contexts     = sourced.toMap
          capturedData = captures.foldLeft(LogContext.empty)((data, captured) => data ++ captured.data)
          accepted <- live
            .zip(captures)
            .traverse { case ((output, index), captured) =>
              val ambient = (Vector(root) ++ output.config.sources.map(s => contexts(s)))
                .foldLeft(LogContext.empty)(_ ++ _)
              captured
                .accepts(LogContext(mdc = output.config.mdc, fields = output.config.fields) ++ ambient ++ capturedData)
                .map { accepts =>
                  if (accepts) Some((output, index, captured)) else None
                }
            }
            .map(_.flatten)
          deliveries <-
            if (accepted.isEmpty) F.pure(Map.empty[Int, Delivery[F]])
            else
              F.delay(AccumulatingRecordWriter.capture(write)).flatMap { payload =>
                accepted
                  .traverse { case (output, index, captured) =>
                    val ambient = (Vector(root) ++ output.config.sources.map(s => contexts(s)))
                      .foldLeft(LogContext.empty)(_ ++ _)
                    val fields = output.config.fields ++ ambient.fields ++ capturedData.fields ++ payload.fields
                    val mdc    = output.config.mdc ++ ambient.mdc ++ capturedData.mdc ++ payload.mdc
                    if (
                      !output.config.fieldFilters
                        .forall(_(fields)) || !output.config.contextFilters.forall(_(LogContext(mdc, fields)))
                    ) F.pure(Option.empty[(Int, Delivery[F])])
                    else {
                      val event = CapturedEvent(meta, fields, payload.body, payload.cause, mdc)
                      captured.prepare(event).map { delivery =>
                        val protectedDelivery = Delivery(
                          output.config.failurePolicy.deliver(delivery.deliver, meta.level, meta.scope, meta.eventName)
                        )
                        Option(index -> protectedDelivery)
                      }
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
