# Source map

Start with [MixedOutputExample](module/example/src/main/scala/org/typelevel/log4cats/experimental/MixedOutputExample.scala),
then follow the files below. Source packages are rooted at `org.typelevel.log4cats.experimental`.

## Core packages

| Package | Responsibility |
| --- | --- |
| root | Application API: loggers, events, record writers, levels, fields, and names. |
| `encoding` | Domain encoders and backend value algebras. |
| `backend` | Native binding, captured records, preparation, and delivery contracts. |
| `context` | Context sources and Local/IOLocal field helpers. |
| `pipeline` | Composition, resource ownership, and runtime entry points. |
| `pipeline.internal` | Record accumulation, preparation, and async queue machinery. |
| `value` | Reference value representation and output factory. |
| `console` | Console output and rendering. |

## Reading order

| Area | Files | What to inspect |
| --- | --- | --- |
| Application API | [Logger](module/core/src/main/scala/org/typelevel/log4cats/experimental/Logger.scala), [Event](module/core/src/main/scala/org/typelevel/log4cats/experimental/Event.scala), [RecordWriter](module/core/src/main/scala/org/typelevel/log4cats/experimental/RecordWriter.scala) | The SAM, immutable event descriptions, and the callback that builds a record. |
| Named loggers | [LoggerFactory](module/core/src/main/scala/org/typelevel/log4cats/experimental/LoggerFactory.scala), [LoggingRuntime](module/core/src/main/scala/org/typelevel/log4cats/experimental/pipeline/LoggingRuntime.scala) | Effectful and unsafe name/class binding; each returned logger retains its bound handles and shares the runtime's outputs and workers. |
| Pipeline | [Pipeline](module/core/src/main/scala/org/typelevel/log4cats/experimental/pipeline/Pipeline.scala) | Configuration compiled into outputs and delivery routing; context capture and preparation. |
| Record construction | [AccumulatingRecordWriter](module/core/src/main/scala/org/typelevel/log4cats/experimental/pipeline/internal/AccumulatingRecordWriter.scala), [Fields](module/core/src/main/scala/org/typelevel/log4cats/experimental/Fields.scala) | Invocation-local accumulation, sealing, and duplicate field resolution. |
| Encoding | [Encoder](module/core/src/main/scala/org/typelevel/log4cats/experimental/encoding/Encoder.scala), [ValueEncoding](module/core/src/main/scala/org/typelevel/log4cats/experimental/encoding/ValueEncoding.scala), [FieldSink](module/core/src/main/scala/org/typelevel/log4cats/experimental/encoding/FieldSink.scala) | Domain encoders, backend value algebras, and direct top-level field emission. |
| Backend contract | [Backend](module/core/src/main/scala/org/typelevel/log4cats/experimental/backend/Backend.scala), [BoundBackend](module/core/src/main/scala/org/typelevel/log4cats/experimental/backend/BoundBackend.scala), [Delivery](module/core/src/main/scala/org/typelevel/log4cats/experimental/backend/Delivery.scala) | Bind a destination to a name, capture integration context, and prepare deferred emission. |
| Context | [ContextSource](module/core/src/main/scala/org/typelevel/log4cats/experimental/context/ContextSource.scala), [LoggingContext](module/core/src/main/scala/org/typelevel/log4cats/experimental/context/LoggingContext.scala), [ScopedFields](module/core/src/main/scala/org/typelevel/log4cats/experimental/context/ScopedFields.scala) | Explicit sources, MTL Ask/Local adapters, and an optional IOLocal field helper. |
| Async delivery | [AsyncDeliveryQueue](module/core/src/main/scala/org/typelevel/log4cats/experimental/pipeline/internal/AsyncDeliveryQueue.scala) | Bounded admission, worker failure, flush barriers, and shutdown. |

Severity helpers support `info(message)`, `info(message, cause)`, `infoEvent(event)`, and
`infoEvent(name)(event)` (and the other severities). Messages/events are deferred until the writer
runs. The cause is strict: it is evaluated once when constructing the logging effect and retained
across executions, preserving construction-time stack traces. Named helpers also capture the name
once at effect construction.

These helpers delegate to the single abstract `Logger.log` method. The pipeline implementation
controls filtering and callback execution; a custom SAM implementation supplies its own behavior.

The [Scala 2 macros](module/core/src/main/scala-2/org/typelevel/log4cats/experimental/LoggerMacro.scala)
and [Scala 3 inline helpers](module/core/src/main/scala-3/org/typelevel/log4cats/experimental/LoggerMacro.scala)
expand severity/event helpers to the same `Logger.log` operation. Read these after the runtime path.

## Follow one log call

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
The [prepared example](module/example/src/main/scala/org/typelevel/log4cats/experimental/PreparedDeliveryExample.scala)
separates those steps explicitly. Replaying a delivery repeats emission without recapturing or encoding.

Context sources are read-only. To scope an effect, use `LoggingContext.withFields` with the same
`Local` read by `LoggingContext.fromAsk`, or `ScopedFields.scope` with its `contextSource` adapter.
Wire that source into the pipeline. `Pipeline.withFields` supplies static defaults;
`Logger` currently has no ambient-context scoping operation.

## Backend implementations

| Backend | Files | Integration choices |
| --- | --- | --- |
| SLF4J | [Slf4jBackend](module/slf4j/src/main/scala/org/typelevel/log4cats/experimental/slf4j/Slf4jBackend.scala), [Slf4jEncoding](module/slf4j/src/main/scala/org/typelevel/log4cats/experimental/slf4j/Slf4jEncoding.scala) | Native logger lookup at binding; native enablement; immutable encoded values; a fresh builder per delivery. |
| OTel | [OtelBackend](module/otel/src/main/scala/org/typelevel/log4cats/experimental/otel/OtelBackend.scala), [AnyValueEncoding](module/otel/src/main/scala/org/typelevel/log4cats/experimental/otel/AnyValueEncoding.scala) | Safe lookup in F; Dispatcher for unsafe lookup; producer telemetry context retained across delivery. |
| Circe | [CirceBackend](module/circe/src/main/scala/org/typelevel/log4cats/experimental/circe/CirceBackend.scala), [JsonEncoding](module/circe/src/main/scala/org/typelevel/log4cats/experimental/circe/JsonEncoding.scala) | Direct interpretation into Json and an application-supplied effectful sink. |
| Console | [ConsoleBackend](module/core/src/main/scala/org/typelevel/log4cats/experimental/console/ConsoleBackend.scala), [ConsoleRenderer](module/core/src/main/scala/org/typelevel/log4cats/experimental/console/ConsoleRenderer.scala) | Reference output using the [Value](module/core/src/main/scala/org/typelevel/log4cats/experimental/value/Value.scala) representation. |

## Contracts worth checking

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
  [async example](module/example/src/main/scala/org/typelevel/log4cats/experimental/AsyncOutputExample.scala).
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
| Is the SAM/frontend usable and lazy? | [InlineFrontendSuite](module/core/src/test/scala/consumer/InlineFrontendSuite.scala) |
| Are factories and bound handles distinct? | [BackendBindingSuite](module/core/src/test/scala/consumer/BackendBindingSuite.scala) |
| Do names share acquired resources? | [LoggerFactorySuite](module/core/src/test/scala/org/typelevel/log4cats/experimental/pipeline/LoggerFactorySuite.scala) |
| How do filters and context compose? | [PipelineSuite](module/core/src/test/scala/org/typelevel/log4cats/experimental/pipeline/PipelineSuite.scala), [ContextSuite](module/core/src/test/scala/org/typelevel/log4cats/experimental/context/ContextSuite.scala) |
| What survives preparation and replay? | [DeliveryContractSuite](module/core/src/test/scala/org/typelevel/log4cats/experimental/pipeline/DeliveryContractSuite.scala), [BackendPreparationSuite](module/core/src/test/scala/consumer/BackendPreparationSuite.scala) |
| What does queue placement/failure mean? | [PipelineTopologySuite](module/core/src/test/scala/org/typelevel/log4cats/experimental/pipeline/PipelineTopologySuite.scala), [AsyncDeliveryQueueSuite](module/core/src/test/scala/org/typelevel/log4cats/experimental/pipeline/internal/AsyncDeliveryQueueSuite.scala) |
| Do native adapters preserve the record? | [Slf4jBackendSuite](module/slf4j/src/test/scala/consumer/Slf4jBackendSuite.scala), [OtelPipelineSuite](module/otel/src/test/scala/org/typelevel/log4cats/experimental/otel/OtelPipelineSuite.scala), [CirceSuite](module/circe/src/test/scala/org/typelevel/log4cats/experimental/circe/CirceSuite.scala) |

## Questions for review

1. Does the SAM/writer/encoding model provide a useful foundation for library and application code?
2. Are factory, bound backend, pipeline, and runtime responsibilities divided appropriately?
3. Which semantics should be fixed by the runtime, and which should be configurable composition:
   timestamp capture, context merging, preparation, and async delivery?
4. Which parts of core belong in optional integrations? Are the macro/inline helpers justified?
5. Should ambient context scoping remain an explicit capability, or be exposed through a logger facade?
