# Source map

Start with [Slf4jExample](module/example/src/main/scala/org/typelevel/log4cats/experimental/Slf4jExample.scala).
It uses the direct SLF4J factory and has no dependency on the optional runtime.
[MixedOutputExample](module/runtime-example/src/main/scala/org/typelevel/log4cats/experimental/MixedOutputExample.scala)
shows the separate runtime experiment: context sources, fan-out, and SLF4J/OTel integration.

## Module boundaries

| Module | Responsibility |
| --- | --- |
| `core` | Logger/factory API, events, field-only context sources, encoding contracts, reference values, and internal record capture. Depends on Cats and cats-mtl; no production cats-effect dependency, pipeline, or context carrier. |
| `slf4j` | Direct SLF4J factory and native encoding/emission. Depends on core. |
| `otel` | Direct OTel factory and native context/record handling. Depends on core. |
| `runtime` | Optional pipeline, backend contracts, context adapters, queues, prepared delivery, and console output. Depends on core. |
| `runtime-slf4j` | SLF4J pipeline adapter; depends on runtime and slf4j. |
| `runtime-otel` | OTel pipeline adapter; depends on runtime and otel. |
| `circe` | JSON encoding and an application-supplied sink for the optional runtime. |
| `example`, `runtime-example` | Direct SLF4J usage and advanced runtime examples, respectively. |

Packages are rooted at `org.typelevel.log4cats.experimental`. Core owns the root API, `encoding`,
`value.Value`, `context.ContextSource`, and shared `internal` record capture. Runtime owns `backend`,
Local/IOLocal helpers in `context`, `pipeline`, `pipeline.internal`, `console`, and
`value.ValueBackend`. `Body` belongs to the root API.

The direct integrations expose `LoggerFactory`; the runtime adapters expose `Backend` for
`Pipeline.fromBackend`. Keeping the adapters separate lets direct users avoid the runtime dependency
while sharing native encoding and emission code. The Scala versions are listed in the README;
all modules currently target the JVM.

## Reading order

| Area | Files | What to inspect |
| --- | --- | --- |
| Application API | [Logger](module/core/src/main/scala/org/typelevel/log4cats/experimental/Logger.scala), [Event](module/core/src/main/scala/org/typelevel/log4cats/experimental/Event.scala), [RecordWriter](module/core/src/main/scala/org/typelevel/log4cats/experimental/RecordWriter.scala) | The SAM, immutable event descriptions, and the callback that builds a record. |
| Factories and runtime control | [LoggerFactory](module/core/src/main/scala/org/typelevel/log4cats/experimental/LoggerFactory.scala), [LoggingRuntime](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/pipeline/LoggingRuntime.scala) | Effectful and direct name/class binding; the optional LoggingRuntime adds flush and prepare, with shared outputs and workers. |
| Pipeline | [Pipeline](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/pipeline/Pipeline.scala) | Configuration compiled into outputs and delivery routing; context capture and preparation. |
| Shared record construction | [AccumulatingRecordWriter](module/core/src/main/scala/org/typelevel/log4cats/experimental/internal/AccumulatingRecordWriter.scala), [Fields](module/core/src/main/scala/org/typelevel/log4cats/experimental/Fields.scala) | Invocation-local accumulation, sealing, and duplicate field resolution. |
| Encoding | [Encoder](module/core/src/main/scala/org/typelevel/log4cats/experimental/encoding/Encoder.scala), [ValueEncoding](module/core/src/main/scala/org/typelevel/log4cats/experimental/encoding/ValueEncoding.scala), [FieldSink](module/core/src/main/scala/org/typelevel/log4cats/experimental/encoding/FieldSink.scala) | Domain encoders, backend value algebras, and direct top-level field emission. |
| Backend contract | [Backend](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/backend/Backend.scala), [BoundBackend](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/backend/BoundBackend.scala), [Delivery](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/backend/Delivery.scala) | Bind a destination to a name, capture backend-typed state, and retain it in deferred emission. |
| Context | [ContextSource](module/core/src/main/scala/org/typelevel/log4cats/experimental/context/ContextSource.scala), [LoggingContext](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/context/LoggingContext.scala), [ScopedFields](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/context/ScopedFields.scala) | Core source construction/composition and Ask adapter; runtime Local scoping and optional IOLocal helper. |
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
returns `LoggerFactory[F]` directly, using either the default provider or an explicit `ILoggerFactory`.
It owns no native resources. Each named logger retains its native SLF4J handle. The default-provider and
explicit-provider overloads accept a core `ContextSource[F]`.
`ContextSource(effect)` wraps an effectful field reader; `ContextSource.const(fields)` supplies fixed
fields, and `ContextSource.empty[F]` supplies none. `ContextSource.fromAsk(ask)` adapts an
`Ask[F, Fields]` without allocating a carrier. `ContextSource(a) ++ ContextSource(b)` reads left
to right; later fields win. Failures stop later reads. `map` transforms the fields on each read.
[Slf4jLogger](module/slf4j/src/main/scala/org/typelevel/log4cats/experimental/slf4j/Slf4jLogger.scala)
reads context once per execution in the calling fiber, installs contextual MDC for native enablement,
then captures the writer and merges context fields followed by call fields. Disabled logging skips
the callback and call-field encoding; context is still read and encoded for the enablement check.
Contextual fields can be encoded for both enablement and emission.

Effective fields retain typed SLF4J key-value representations and are also mirrored into MDC using
their native values' string representations (`null` becomes `"null"`). Event-name metadata wins over
an `event.name` field. Both native enablement and emission run in synchronous `F.delay` actions that save, replace,
and restore MDC in `finally`. They introduce no blocking-pool boundary; the configured SLF4J
provider and appenders execute on the calling thread. Unrelated thread MDC is excluded during
those actions.
SLF4J owns the timestamp; this path reads no clock and uses no routing structure.

[Otel.resource](module/otel/src/main/scala/org/typelevel/log4cats/experimental/otel/Otel.scala)
returns `Resource[F, LoggerFactory[F]]`. It owns the Dispatcher used for unsafe lookup and borrows
the supplied provider. Safe lookup stays in the caller effect.
[OtelLogger](module/otel/src/main/scala/org/typelevel/log4cats/experimental/otel/OtelLogger.scala)
captures event time and telemetry context during execution, checks native enablement, then captures,
encodes, and emits the record. Observation time is sampled on emission.

Both direct implementations defer callbacks until execution and skip them when disabled.
Neither constructs a pipeline. Their native encoding/emission helpers are also used by the optional
runtime adapters, so exception handling, structured values, and event names share one implementation.
SLF4J's context reader can obtain request and telemetry fields from application-owned carriers.
It does not allocate or mutate an ambient scope, or extract telemetry fields automatically.
`LoggingContext.withFields` (Local scoping), `ScopedFields` (IOLocal scoping), and pipeline policies
remain in the optional runtime. The read-only Ask adapter lives on core `ContextSource`.
`Otel.resource(provider, source)` accepts the same core field source, reading it after native
enablement and before record construction. Call fields override source fields. Native telemetry
context is still captured independently by the OTel implementation.

## Optional runtime: follow one log call

`Pipeline.fromBackend` introduces a destination. `Pipeline.resource` exposes a `LoggerFactory`;
`Pipeline.runtimeResource` exposes `LoggingRuntime`, which implements that factory and adds
`flush` and `prepare`. Follow resource acquisition (`runtimeResource → compile`), named logger
construction (`fromName → bind → bound`), then each log execution (`prepare → deliver`).
`fromName`/`fromClass` return `F[Logger[F]]`; `getLoggerFromName`/`getLoggerFromClass` return
`Logger[F]` directly, matching log4cats naming. `LoggingRuntime.fromName` invokes effectful backend
binding; `getLoggerFromName` invokes synchronous binding.
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
    → timestamp, root/source context, and typed backend context capture
    → project backend context fields in output order
    → backend acceptance checks
    → writer callback once, if any backend accepts
    → effective fields and field filters, per output
    → preparation of every remaining output
    → delivery, directly or through configured queues
```

`AccumulatingRecordWriter.capture` produces `RecordData`; `CapturedEvent` combines that payload with metadata
and effective fields for each branch. Neither `CapturedEvent` nor `EncodedEvent` contains native context.
`BoundBackend.Context` is an associated type: `capture` returns that backend's state, and both
`accepts` and `prepare` consume it. Stateless backends use `Unit`; OTel uses the full native `Ctx`.
`Pipeline.CapturedOutput` closes over the backend/state pair, preserving its type without casts.
`BoundBackend.prepare(event, context)` encodes the record and retains state in a `Delivery` closure.
`LoggingRuntime` then runs the prepared delivery through the compiled routing. The configuration
compiler accumulates filters, defaults, and source references on outputs. Runtime routing contains
direct output, sequencing, and queue boundaries.
The [prepared example](module/runtime-example/src/main/scala/org/typelevel/log4cats/experimental/PreparedDeliveryExample.scala)
separates those steps explicitly. Replaying a delivery repeats emission without recapturing or encoding.

Context sources are read-only. To scope an effect, use `LoggingContext.withFields` with the same
`Local` read by `ContextSource.fromAsk`, or `ScopedFields.scope` with its `contextSource` adapter.
Wire that source into the pipeline. `Pipeline.withFields` supplies static defaults;
`Logger` currently has no ambient-context scoping operation.

An OTel runtime adapter can also project fields from that same native capture:
`OtelBackend.resource(provider, fields: Ctx => Fields)`. The projection is synchronous and optional;
its default is empty. Projected fields can enrich sibling outputs while the native state remains
private to OTel. The runtime performs no native-context merging and uses no Vault attachments.

## Optional runtime adapters

| Backend | Files | Integration choices |
| --- | --- | --- |
| SLF4J | [Slf4jBackend](module/runtime-slf4j/src/main/scala/org/typelevel/log4cats/experimental/slf4j/Slf4jBackend.scala), [Slf4jEncoding](module/slf4j/src/main/scala/org/typelevel/log4cats/experimental/slf4j/Slf4jEncoding.scala) | Native logger lookup at binding; native enablement; immutable encoded values; a fresh builder and scoped MDC per delivery. |
| OTel | [OtelBackend](module/runtime-otel/src/main/scala/org/typelevel/log4cats/experimental/otel/OtelBackend.scala), [AnyValueEncoding](module/otel/src/main/scala/org/typelevel/log4cats/experimental/otel/AnyValueEncoding.scala) | Safe lookup in F; Dispatcher for unsafe lookup; producer telemetry context retained across delivery. |
| Circe | [CirceBackend](module/circe/src/main/scala/org/typelevel/log4cats/experimental/circe/CirceBackend.scala), [JsonEncoding](module/circe/src/main/scala/org/typelevel/log4cats/experimental/circe/JsonEncoding.scala) | Direct interpretation into Json and an application-supplied effectful sink. |
| Console | [ConsoleBackend](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/console/ConsoleBackend.scala), [ConsoleRenderer](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/console/ConsoleRenderer.scala) | Reference output using the [Value](module/core/src/main/scala/org/typelevel/log4cats/experimental/value/Value.scala) representation. |

The runtime's early SLF4J enablement check has level/event-name metadata; effective fields reach MDC
on delivery. MDC-dependent enablement using ambient fields is supported by the direct factory's
context reader, not by the runtime's early check.

## Runtime contracts worth checking

- **Laziness:** for pipeline-provided loggers, message/event evaluation stays inside the writer
  callback. Minimum/metadata and native acceptance checks can skip it; field filters require
  constructing the payload first.
- **Fields:** static defaults precede ambient source fields, then backend-projected fields, then call fields;
  later values win.
  Repeated body/cause writes also use the last value. A writer is sealed after its callback.
- **Context:** a source attached above fan-out is read once per log occurrence. Attaching the same
  source object separately to two branches reads it twice when both survive coarse filtering.
  Branch fields remain isolated.
  Every output surviving minimum/metadata filters captures before any native acceptance check.
  Each captured state stays with its own backend and is retained across prepared replay and queues.
  `contextFields` projections merge in output order across the surviving outputs, including
  projections from outputs that subsequently reject the record. Projected fields are visible to
  all selected outputs; opaque native states are never shared between backends.
- **Preparation:** outputs passing native acceptance and field filters finish encoding before any
  output emits or queues the record. Field-rejected outputs do not encode.
  Preparation failure emits nothing. Delivery is sequential and fail-fast; earlier emissions or
  queue admissions survive a later failure. Fan-out admissions are not transactional.
- **Values:** encoders run separately for each selected backend.
  Event descriptions retain domain references until preparation. The supplied encodings snapshot
  bytes during encoding. Throwable references and captured backend states are not deep copies.
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
| Do context sources compose and capture lazily? | [ContextSourceSuite](module/core/src/test/scala/org/typelevel/log4cats/experimental/context/ContextSourceSuite.scala) |
| Does SLF4J work without the runtime? | [Slf4jFactorySuite](module/slf4j/src/test/scala/consumer/Slf4jFactorySuite.scala) |
| Does direct OTel lookup/context handling work? | [OtelFactorySuite](module/otel/src/test/scala/org/typelevel/log4cats/experimental/otel/OtelFactorySuite.scala) |
| Is the SAM/frontend usable and lazy? | [InlineFrontendSuite](module/core/src/test/scala/consumer/InlineFrontendSuite.scala) |
| Are factories/bound handles distinct, and captured context types isolated? | [BackendBindingSuite](module/runtime/src/test/scala/consumer/BackendBindingSuite.scala) |
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
4. Are the macro/inline helpers justified? Are shared record capture and typed backend state the right boundaries for native implementations?
5. Should ambient context scoping remain an explicit capability, or be exposed through a logger facade?
