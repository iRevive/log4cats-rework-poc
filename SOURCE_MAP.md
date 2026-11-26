# Source map

Start with [Slf4jExample](module/example/src/main/scala/org/typelevel/log4cats/experimental/Slf4jExample.scala).
It uses the direct SLF4J factory and has no dependency on the optional runtime.
[MixedOutputExample](module/runtime-example/src/main/scala/org/typelevel/log4cats/experimental/MixedOutputExample.scala)
shows the separate runtime experiment: context sources, fan-out, and SLF4J/OTel integration.

## Module boundaries

| Module | Responsibility |
| --- | --- |
| `core` | Logger/factory API, events, encoding contracts, reference values, and internal record capture. Depends on Cats; no pipeline, Vault, or context carrier. |
| `slf4j` | Direct SLF4J factory and native encoding/emission. Depends on core. |
| `otel` | Direct OTel factory and native context/record handling. Depends on core. |
| `runtime` | Optional pipeline, backend contracts, context adapters, queues, prepared delivery, and console output. Depends on core. |
| `runtime-slf4j`, `runtime-otel` | Adapt the native implementations to the optional runtime. |
| `circe` | JSON encoding and an application-supplied sink for the optional runtime. |
| `example`, `runtime-example` | Direct SLF4J usage and advanced runtime examples, respectively. |

Packages are rooted at `org.typelevel.log4cats.experimental`. Core owns the root API, `encoding`,
`value.Value`, and shared `internal` record capture. Runtime owns `backend`, `context`, `pipeline`,
`pipeline.internal`, `console`, and `value.ValueBackend`. `Body` belongs to the root API.

## Reading order

| Area | Files | What to inspect |
| --- | --- | --- |
| Application API | [Logger](module/core/src/main/scala/org/typelevel/log4cats/experimental/Logger.scala), [Event](module/core/src/main/scala/org/typelevel/log4cats/experimental/Event.scala), [RecordWriter](module/core/src/main/scala/org/typelevel/log4cats/experimental/RecordWriter.scala) | The SAM, immutable event descriptions, and the callback that builds a record. |
| Runtime named loggers | [LoggerFactory](module/core/src/main/scala/org/typelevel/log4cats/experimental/LoggerFactory.scala), [LoggingRuntime](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/pipeline/LoggingRuntime.scala) | Effectful and unsafe name/class binding; each returned logger retains its bound handles and shares the runtime's outputs and workers. |
| Pipeline | [Pipeline](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/pipeline/Pipeline.scala) | Configuration compiled into outputs and delivery routing; context capture and preparation. |
| Shared record construction | [AccumulatingRecordWriter](module/core/src/main/scala/org/typelevel/log4cats/experimental/internal/AccumulatingRecordWriter.scala), [Fields](module/core/src/main/scala/org/typelevel/log4cats/experimental/Fields.scala) | Invocation-local accumulation, sealing, and duplicate field resolution. |
| Encoding | [Encoder](module/core/src/main/scala/org/typelevel/log4cats/experimental/encoding/Encoder.scala), [ValueEncoding](module/core/src/main/scala/org/typelevel/log4cats/experimental/encoding/ValueEncoding.scala), [FieldSink](module/core/src/main/scala/org/typelevel/log4cats/experimental/encoding/FieldSink.scala) | Domain encoders, backend value algebras, and direct top-level field emission. |
| Backend contract | [Backend](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/backend/Backend.scala), [BoundBackend](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/backend/BoundBackend.scala), [Delivery](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/backend/Delivery.scala) | Bind a destination to a name, capture integration context, and prepare deferred emission. |
| Context | [ContextSource](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/context/ContextSource.scala), [LoggingContext](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/context/LoggingContext.scala), [ScopedFields](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/context/ScopedFields.scala) | Explicit sources, MTL Ask/Local adapters, and an optional IOLocal field helper. |
| Async delivery | [AsyncDeliveryQueue](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/pipeline/internal/AsyncDeliveryQueue.scala) | Bounded admission, worker failure, flush barriers, and shutdown. |

Severity helpers support `info(message)`, `info(message, cause)`, `infoEvent(event)`, and
`infoEvent(name)(event)` (and the other severities). Messages/events are deferred until the writer
runs. The cause is strict: it is evaluated once when constructing the logging effect and retained
across executions, preserving construction-time stack traces. Named helpers also capture the name
once at effect construction.

These helpers delegate to the single abstract `Logger.log` method. Each implementation
controls filtering and callback execution; a custom SAM implementation supplies its own behavior.

The [Scala 2 macros](module/core/src/main/scala-2/org/typelevel/log4cats/experimental/LoggerMacro.scala)
and [Scala 3 inline helpers](module/core/src/main/scala-3/org/typelevel/log4cats/experimental/LoggerMacro.scala)
expand severity/event helpers to the same `Logger.log` operation. Read these after the logging path.

## Direct native logging

[Slf4j.factory](module/slf4j/src/main/scala/org/typelevel/log4cats/experimental/slf4j/Slf4j.scala)
returns `F[LoggerFactory[F]]`, using either the default provider or an explicit `ILoggerFactory`.
It owns no native resources. Each named logger retains its native SLF4J handle.
[Slf4jLogger](module/slf4j/src/main/scala/org/typelevel/log4cats/experimental/slf4j/Slf4jLogger.scala)
checks native enablement, captures the writer once, encodes the record, and emits on a blocking
boundary. SLF4J owns the timestamp; this path reads no clock and uses no Vault or routing structure.

[Otel.resource](module/otel/src/main/scala/org/typelevel/log4cats/experimental/otel/Otel.scala)
returns `Resource[F, LoggerFactory[F]]`. It owns the Dispatcher used for unsafe lookup and borrows
the supplied provider. Safe lookup stays in the caller effect.
[OtelLogger](module/otel/src/main/scala/org/typelevel/log4cats/experimental/otel/OtelLogger.scala)
captures event time and telemetry context during execution, checks native enablement, then captures,
encodes, and emits the record. Observation time is sampled on emission.

Both direct implementations defer callbacks until execution and skip them when disabled.
Neither constructs a pipeline. Their native encoding/emission helpers are also used by the optional
runtime adapters, so exception handling, structured values, and event names share one implementation.
Ambient field helpers and pipeline policies belong to the optional runtime; the direct factory APIs
do not currently accept a context source.

## Optional runtime: follow one log call

`Pipeline.fromBackend` introduces a destination. `Pipeline.resource` exposes a `LoggerFactory`;
`Pipeline.runtimeResource` exposes `LoggingRuntime`, which implements that factory and adds
`flush` and `prepare`. Follow resource acquisition (`runtimeResource → compile`), named logger
construction (`fromName → bind → bound`), then each log execution (`prepare → deliver`).
`LoggingRuntime.fromName` invokes safe backend binding; `fromNameUnsafe` invokes synchronous binding.
`Backend` defines safe and unsafe binding explicitly. SLF4J suspends its synchronous lookup;
OTel composes the provider's effect directly and uses its Dispatcher only for unsafe lookup.
`BoundBackend` is a separate emission handle with no binding operations. Name-independent outputs
use `Backend.constant(handle)` to opt into sharing a handle; Value, Console, and Circe constructors
return those factories. Factory lookups bind rather than caching by name.
Logging through the returned logger reuses those handles. Naming does not acquire another output
or worker. `LoggingRuntime.prepare`, unlike a retained logger, binds on each execution of its effect.

```text
pipeline-provided logger.log(...) creates F[Unit]
  execution
    → minimum-level and metadata filters
    → timestamp, root/source context, and backend context capture
    → backend acceptance checks
    → writer callback once, if any backend accepts
    → effective fields and field filters, per output
    → preparation of every remaining output
    → delivery, directly or through configured queues
```

`AccumulatingRecordWriter.capture` produces `RecordData`; `CapturedEvent` combines that payload with metadata
and effective context for each branch. `BoundBackend.prepare` encodes it into a `Delivery`.
`LoggingRuntime` then runs the prepared delivery through the compiled routing. The configuration
compiler accumulates filters, defaults, and source references on outputs. Runtime routing contains
direct output, sequencing, and queue boundaries.
The [prepared example](module/runtime-example/src/main/scala/org/typelevel/log4cats/experimental/PreparedDeliveryExample.scala)
separates those steps explicitly. Replaying a delivery repeats emission without recapturing or encoding.

Context sources are read-only. To scope an effect, use `LoggingContext.withFields` with the same
`Local` read by `LoggingContext.fromAsk`, or `ScopedFields.scope` with its `contextSource` adapter.
Wire that source into the pipeline. `Pipeline.withFields` supplies static defaults;
`Logger` currently has no ambient-context scoping operation.

## Optional runtime adapters

| Backend | Files | Integration choices |
| --- | --- | --- |
| SLF4J | [Slf4jBackend](module/runtime-slf4j/src/main/scala/org/typelevel/log4cats/experimental/slf4j/Slf4jBackend.scala), [Slf4jEncoding](module/slf4j/src/main/scala/org/typelevel/log4cats/experimental/slf4j/Slf4jEncoding.scala) | Native logger lookup at binding; native enablement; immutable encoded values; a fresh builder per delivery. |
| OTel | [OtelBackend](module/runtime-otel/src/main/scala/org/typelevel/log4cats/experimental/otel/OtelBackend.scala), [AnyValueEncoding](module/otel/src/main/scala/org/typelevel/log4cats/experimental/otel/AnyValueEncoding.scala) | Safe lookup in F; Dispatcher for unsafe lookup; producer telemetry context retained across delivery. |
| Circe | [CirceBackend](module/circe/src/main/scala/org/typelevel/log4cats/experimental/circe/CirceBackend.scala), [JsonEncoding](module/circe/src/main/scala/org/typelevel/log4cats/experimental/circe/JsonEncoding.scala) | Direct interpretation into Json and an application-supplied effectful sink. |
| Console | [ConsoleBackend](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/console/ConsoleBackend.scala), [ConsoleRenderer](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/console/ConsoleRenderer.scala) | Reference output using the [Value](module/core/src/main/scala/org/typelevel/log4cats/experimental/value/Value.scala) representation. |

## Runtime contracts worth checking

- **Laziness:** for pipeline-provided loggers, message/event evaluation stays inside the writer
  callback. Minimum/metadata and native acceptance checks can skip it; field filters require
  constructing the payload first.
- **Fields:** static defaults precede ambient context, which precedes call fields; later values win.
  Repeated body/cause writes also use the last value. A writer is sealed after its callback.
- **Context:** a source attached above fan-out is read once per log occurrence. Attaching the same
  source object separately to two branches reads it twice when both survive coarse filtering.
  Branch fields remain isolated.
  Every output surviving minimum/metadata filters captures before any native acceptance check.
  Those backend Vault captures merge in output order and are visible to all surviving outputs,
  including captures from outputs that subsequently reject the record.
  Native logger handles stay outside the Vault; Vault entries do not automatically become fields.
- **Preparation:** outputs passing native acceptance and field filters finish encoding before any
  output emits or queues the record. Field-rejected outputs do not encode.
  Preparation failure emits nothing. Delivery is sequential and fail-fast; earlier emissions or
  queue admissions survive a later failure. Fan-out admissions are not transactional.
- **Values:** encoders are expected to be pure; they run separately for each selected backend.
  Event descriptions retain domain references until preparation. The supplied encodings snapshot
  bytes during encoding. Throwable references and opaque attachments are not deep copies.
  Empty/byte/nonfinite encodings are backend policies and are not universally reversible.
- **Time:** the pipeline currently samples one producer timestamp after coarse filtering. OTel
  retains it as event time and samples observation time on delivery. The SLF4J fluent adapter cannot
  override the provider's native timestamp. Timestamp ownership is still a review question.
- **Async:** queue placement changes delivery topology; see the
  [async example](module/runtime-example/src/main/scala/org/typelevel/log4cats/experimental/AsyncOutputExample.scala).
  Admission applies backpressure. Flush is a barrier for prior admitted work, not an exporter flush.
  Worker failure stops delivery and is exposed by subsequent admission, flush, and resource release.
  Cancellation racing admission can leave an admitted record. Shutdown drains admitted work before
  releasing outputs; a timeout cancels the worker, but cannot force uncancelable actions to stop.
- **Ownership:** each resource acquisition creates an independent runtime. Named loggers share its
  destinations and workers. Borrowed SLF4J/OTel providers remain application-owned. Unsafe OTel
  binding may block and should use a blocking boundary when called from an IO program.

## Tests to read

| Question | Suite |
| --- | --- |
| Does SLF4J work without the runtime? | [Slf4jFactorySuite](module/slf4j/src/test/scala/consumer/Slf4jFactorySuite.scala) |
| Does direct OTel lookup/context handling work? | [OtelFactorySuite](module/otel/src/test/scala/org/typelevel/log4cats/experimental/otel/OtelFactorySuite.scala) |
| Is the SAM/frontend usable and lazy? | [InlineFrontendSuite](module/core/src/test/scala/consumer/InlineFrontendSuite.scala) |
| Are factories and bound handles distinct? | [BackendBindingSuite](module/runtime/src/test/scala/consumer/BackendBindingSuite.scala) |
| Do names share acquired resources? | [LoggerFactorySuite](module/runtime/src/test/scala/org/typelevel/log4cats/experimental/pipeline/LoggerFactorySuite.scala) |
| How do filters and context compose? | [PipelineSuite](module/runtime/src/test/scala/org/typelevel/log4cats/experimental/pipeline/PipelineSuite.scala), [ContextSuite](module/runtime/src/test/scala/org/typelevel/log4cats/experimental/context/ContextSuite.scala) |
| What survives preparation and replay? | [DeliveryContractSuite](module/runtime/src/test/scala/org/typelevel/log4cats/experimental/pipeline/DeliveryContractSuite.scala), [BackendPreparationSuite](module/runtime/src/test/scala/consumer/BackendPreparationSuite.scala) |
| What does queue placement/failure mean? | [PipelineTopologySuite](module/runtime/src/test/scala/org/typelevel/log4cats/experimental/pipeline/PipelineTopologySuite.scala), [AsyncDeliveryQueueSuite](module/runtime/src/test/scala/org/typelevel/log4cats/experimental/pipeline/internal/AsyncDeliveryQueueSuite.scala) |
| Do native adapters preserve the record? | [Slf4jBackendSuite](module/runtime-slf4j/src/test/scala/consumer/Slf4jBackendSuite.scala), [OtelPipelineSuite](module/runtime-otel/src/test/scala/org/typelevel/log4cats/experimental/otel/OtelPipelineSuite.scala), [CirceSuite](module/circe/src/test/scala/org/typelevel/log4cats/experimental/circe/CirceSuite.scala) |

## Questions for review

1. Does the SAM/writer/encoding model provide a useful foundation for library and application code?
2. Does the core/native integration/runtime split keep ordinary usage small?
3. Which semantics should be fixed by the runtime, and which should be configurable composition:
   timestamp capture, context merging, preparation, and async delivery?
4. Are the macro/inline helpers justified? Is shared record capture the right boundary for native implementations?
5. Should ambient context scoping remain an explicit capability, or be exposed through a logger facade?
