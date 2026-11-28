# Source map

Start with [Slf4jExample](module/example/src/main/scala/org/typelevel/log4cats/experimental/Slf4jExample.scala)
for direct SLF4J logging. It has no runtime dependency.
[MixedOutputExample](module/runtime-example/src/main/scala/org/typelevel/log4cats/experimental/MixedOutputExample.scala)
uses the optional runtime to send records to SLF4J and OTel with different level thresholds.

## Module boundaries

| Module | Responsibility |
| --- | --- |
| `core` | Logger/factory API, events, MDC and structured field sources, encoding, reference values, and internal record capture. Uses Cats and cats-mtl; cats-effect is a test dependency only. |
| `slf4j` | Direct SLF4J factory, encoding, and emission. Depends on core. |
| `otel` | Direct otel4s factory, encoding, telemetry context capture, and emission. Depends on core. |
| `runtime` | Pipelines, backend contracts, context scoping helpers, queues, prepared delivery, and console output. Depends on core. |
| `runtime-slf4j` | SLF4J pipeline adapter. Depends on runtime and slf4j. |
| `runtime-otel` | OTel pipeline adapter. Depends on runtime and otel. |
| `circe` | JSON encoding and a runtime backend with an application-supplied sink. Depends on runtime. |
| `example`, `runtime-example` | Direct SLF4J usage and runtime examples, respectively. |

All packages start with `org.typelevel.log4cats.experimental`. Core contains the root API (including
`Body`), `encoding`, `value.Value`, `context.ContextSource`, and `internal` record capture.
Runtime adds `backend`, context scoping helpers, `pipeline`, `pipeline.internal`, `console`, and
`value.ValueBackend`.

Direct integrations provide `LoggerFactory`. Runtime adapters reuse their encoding and emission
code and provide `Backend` values for `Pipeline.fromBackend`. Direct users do not need these adapters
or runtime. See the [README module table](README.md#modules) for Scala versions; all modules target
the JVM. Dependencies are set in [build.sbt](build.sbt).

## Reading order

| Area | Files | What to inspect |
| --- | --- | --- |
| Application API | [Logger](module/core/src/main/scala/org/typelevel/log4cats/experimental/Logger.scala), [Event](module/core/src/main/scala/org/typelevel/log4cats/experimental/Event.scala), [RecordWriter](module/core/src/main/scala/org/typelevel/log4cats/experimental/RecordWriter.scala) | The single abstract logging method, event descriptions, and record construction callback. |
| Factories and runtime control | [LoggerFactory](module/core/src/main/scala/org/typelevel/log4cats/experimental/LoggerFactory.scala), [LoggingRuntime](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/pipeline/LoggingRuntime.scala) | Effectful and direct logger lookup; runtime flush and prepare. |
| Pipeline | [Pipeline](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/pipeline/Pipeline.scala) | Output configuration, context capture, preparation, and delivery order. |
| Shared record construction | [AccumulatingRecordWriter](module/core/src/main/scala/org/typelevel/log4cats/experimental/internal/AccumulatingRecordWriter.scala), [Fields](module/core/src/main/scala/org/typelevel/log4cats/experimental/Fields.scala) | One writer per callback, sealing, and duplicate field names. |
| Encoding | [Encoder](module/core/src/main/scala/org/typelevel/log4cats/experimental/encoding/Encoder.scala), [ValueEncoding](module/core/src/main/scala/org/typelevel/log4cats/experimental/encoding/ValueEncoding.scala), [FieldSink](module/core/src/main/scala/org/typelevel/log4cats/experimental/encoding/FieldSink.scala) | Domain encoders, backend value construction, and top-level field encoding. |
| Backend contract | [Backend](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/backend/Backend.scala), [BoundBackend](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/backend/BoundBackend.scala), [Delivery](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/backend/Delivery.scala) | Bind a logger name, capture typed backend state, and retain it for delivery. |
| Context | [ContextSource](module/core/src/main/scala/org/typelevel/log4cats/experimental/context/ContextSource.scala), [LoggingContext](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/context/LoggingContext.scala), [ScopedFields](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/context/ScopedFields.scala) | Read and combine fields in core; scope fields with Local or IOLocal in runtime. |
| Async delivery | [AsyncDeliveryQueue](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/pipeline/internal/AsyncDeliveryQueue.scala) | Bounded admission, worker failure, flush barriers, and shutdown. |

`Logger` has one abstract method, `log(level, eventName, write)`, so it can be implemented with a
lambda (a single abstract method, or SAM). Helpers such as `info(message)`, `info(message, cause)`,
`infoEvent(event)`, and `infoEvent(name)(event)` all call it. The other severities have the same helpers.

Message and event expressions run inside the writer callback. Cause arguments and event names are
evaluated once when the logging effect is constructed. Reusing that effect retains the same cause
and name. Custom `Logger` implementations control when and how often they invoke the callback;
the supplied implementations defer it until execution and skip it when disabled.

The [Scala 2 macros](module/core/src/main/scala-2/org/typelevel/log4cats/experimental/LoggerMacro.scala)
and [Scala 3 inline helpers](module/core/src/main/scala-3/org/typelevel/log4cats/experimental/LoggerMacro.scala)
implement these helpers. Read them after the logging path.

## Direct SLF4J logging

[Slf4j.factory](module/slf4j/src/main/scala/org/typelevel/log4cats/experimental/slf4j/Slf4j.scala)
returns `LoggerFactory[F]`. Overloads accept the default or an explicit `ILoggerFactory`, with an
optional `ContextSource[F]`. The factory owns no native resources. Each returned logger retains its
native handle. `fromName` / `fromClass` perform lookup in `F`; `getLoggerFromName` /
`getLoggerFromClass` perform lookup directly.

[Slf4jLogger](module/slf4j/src/main/scala/org/typelevel/log4cats/experimental/slf4j/Slf4jLogger.scala)
uses these steps with the default MDC-aware policy:

1. Read diagnostic and structured context in the calling fiber.
2. Install diagnostic MDC for the native level check.
3. If enabled, invoke the writer once and merge each channel with its call values.
4. Encode the structured record and emit through a fresh SLF4J builder with the captured MDC.

The default `Slf4j.Enablement.MdcAware` policy reads context even for disabled calls; it skips the
writer and structured encoding. `Slf4j.Enablement.Native` checks before reading logical context,
so disabled direct calls skip context reads and MDC installation/restoration as well. MDC already holds
strings. Call values override context values independently in each channel. Same-key MDC and
structured values coexist. Event-name metadata overrides an `event.name` structured field and is
emitted only as a key-value pair; explicit MDC `event.name` remains independent.

Structured fields go only to native key-value pairs, and diagnostic values go only to native MDC.
MDC-aware checks and emission save and restore thread MDC in a `finally` block.
`Slf4j.Config.mdcPolicy` defaults to `MdcPolicy.Isolate`, replacing thread MDC during those actions.
`MdcPolicy.Overlay` retains thread entries and writes logical MDC over matching keys. Both restore
the full previous map, including after native failure. Overlay reads thread state at each action;
prepared and async delivery do not capture producer thread MDC. Both actions run in `F.delay`,
without a blocking-pool boundary, so providers and appenders run on the calling thread. SLF4J supplies the timestamp; this
path reads no clock.

The SLF4J Native policy calls `isEnabledForLevel` without changing native thread state. A provider
may still run filters consulting thread MDC. The default MdcAware policy installs captured logical
MDC according to the configured isolation/overlay policy and restores thread MDC afterward.
Neither forwards event-name metadata to native acceptance.
`Slf4j.factory` and `Slf4jBackend.acquire` both accept the explicit policy.

## Direct OTel logging

[Otel.resource](module/otel/src/main/scala/org/typelevel/log4cats/experimental/otel/Otel.scala)
returns `Resource[F, LoggerFactory[F]]`. It owns a Dispatcher for direct lookup and leaves the
provider application-owned. Effectful lookup uses the caller's effect. Direct lookup runs the
provider effect through `Dispatcher.unsafeRunSync` and can block; prefer `fromName` / `fromClass`.

[OtelLogger](module/otel/src/main/scala/org/typelevel/log4cats/experimental/otel/OtelLogger.scala)
runs these steps for each logging execution:

1. Sample event time and capture native telemetry context.
2. Check native enablement using that context, severity, and event name.
3. If enabled, read the optional two-channel source and invoke the writer once.
4. Merge source values with call values within each channel, encode, and emit using the captured telemetry context.

OTel merges MDC strings and structured fields into attributes at its adapter boundary. Structured
fields win collisions. `MdcMapping.Flat` is the default; `MdcMapping.Prefixed("mdc.")` prefixes
diagnostic keys literally. Explicit structured keys also win prefix collisions. Native telemetry
context remains separate. Call values override source values within each channel. Observation time is sampled during emission. A cause supplies
`exception.type`, `exception.message`, and `exception.stacktrace`, overriding fields with those
names. Event names use native OTel event-name metadata.

## Context sources and scoping

`ContextSource(effect)` reads `F[LogContext]`, with independent `mdc: Mdc` and `fields: Fields`
channels. `fromFields(effect)` and `fromMdc(effect)` adapt existing readers. `const` accepts either
`LogContext` or `Fields`, and `empty[F]` supplies neither. `fromAsk` adapts `Ask[F, Fields]`;
`fromContextAsk` adapts `Ask[F, LogContext]`. Combining sources with `a ++ b` reads left to right,
with later values winning independently in each channel. A failed read stops later reads.
`map` transforms the two-channel context on each read.

Applications own the carriers read by sources, such as an `IOLocal` or MTL environment. SLF4J does
not extract telemetry fields automatically. Runtime provides `LoggingContext.withContext` /
`withMdc` for `Local[F, LogContext]`, and `ScopedContext` for fiber-local two-channel scopes.
`ScopedContext.source` connects the scoped values to direct factories or pipelines. The field-only
`LoggingContext.withFields`, `ScopedFields`, and their source adapters remain available.
`Logger` has no context scoping operation.

## Optional runtime: follow one log call

`Pipeline.fromBackend` adds an output; `++` combines outputs. `Pipeline.resource` provides a
`LoggerFactory`. `Pipeline.runtimeResource` provides a `LoggingRuntime`, which implements that
factory and adds `flush` and `prepare`.

Resource acquisition compiles the pipeline and acquires its outputs and queues. Logger lookup
binds each backend to a name: `fromName` uses effectful `Backend.bind`, while `getLoggerFromName`
uses synchronous `bindUnsafe`. The returned logger reuses those bound handles. Lookup does not
cache loggers by name or acquire another output or worker. `LoggingRuntime.prepare` binds on
each execution, even when reusing the same preparation effect.

`BoundBackend` handles capture, acceptance, preparation, and emission; it has no lookup methods.
`Backend.constant(handle)` shares one handle across names. Value, Console, and Circe constructors
use this approach. SLF4J suspends native lookup in `F.delay`; OTel uses the provider effect for
`bind` and a Dispatcher for `bindUnsafe`.

Each logging execution follows this sequence. Capture and preparation failures stop it; delivery
errors follow the output's configured policy:

```text
minimum-level and metadata filters
  → timestamp, root/source MDC and structured fields, and typed backend context capture
  → merge projected backend enrichment in output order
  → backend acceptance checks
  → writer callback once, if any backend accepts
  → for each accepted output: merge both channels, apply field/context filters, prepare if kept
  → deliver in pipeline order, directly or through queues
```

If all outputs fail the minimum-level or metadata filters, the runtime reads no clock or context
and skips the callback. Field filters run after the callback because they need its fields.

`AccumulatingRecordWriter.capture` creates `RecordData`. `CapturedEvent` adds metadata and merged
MDC/structured fields for one output. Neither `CapturedEvent` nor `EncodedEvent` holds native context.
`BoundBackend.Context` is an associated type: `capture` returns it, and `accepts` and `prepare`
consume it. Stateless backends use `Unit`; OTel uses its native `Ctx`.
`Pipeline.CapturedOutput` retains the backend and its captured state together without casts.

`BoundBackend.prepare` returns a `Delivery` that retains encoded data and backend state.
The default implementation encodes before returning; SLF4J and OTel override preparation to use
their native helpers. Custom backends must keep encoding in preparation if they want the same
failure and replay behavior. The runtime waits for every selected output's preparation before
starting delivery.

The [prepared example](module/runtime-example/src/main/scala/org/typelevel/log4cats/experimental/PreparedDeliveryExample.scala)
shows preparation and delivery as separate calls. With the supplied backends, replay repeats
emission without re-reading context, re-running the writer, or re-encoding the payload.
Native builders are created afresh; OTel observation time is sampled again on each emission.

## Runtime fields and context

MDC and structured fields merge independently in this order, with later values winning within
each channel. Same-key values across channels coexist. `filterFields` sees only structured fields;
`filterContext` sees both channels after call capture:

1. Static defaults from `Pipeline.withFields` and `withMdc`.
2. Root context, then sources attached along the output's branch from outer to inner.
3. Enrichment projected from captured backend context, in output order.
4. Values written by the call.

Outer static defaults override inner defaults; repeated `withFields` / `withMdc` calls use the later values.
A source attached above `a ++ b` is read once if either branch survives the initial filters.
Attaching the same source object separately to each branch reads it twice if both survive.
Source enrichment stays on its branch.

Every output surviving the initial filters captures its backend context before any native
acceptance check, provided earlier captures succeed. `contextData` projections (by default wrapping the field-only `contextFields` hook) from all those
outputs are shared, including enrichment from outputs that later reject the record. The native states remain
with their own backends through preparation, queues, and replay.

`OtelBackend.resource(provider, fields: Ctx => Fields)` projects fields from OTel's captured context.
The projection is synchronous and defaults to empty. Other outputs see the projected fields,
while the native context remains private to the OTel output. The runtime does not merge native
contexts.

## Runtime adapters

| Backend | Files | Integration choices |
| --- | --- | --- |
| SLF4J | [Slf4jBackend](module/runtime-slf4j/src/main/scala/org/typelevel/log4cats/experimental/slf4j/Slf4jBackend.scala), [Slf4jEncoding](module/slf4j/src/main/scala/org/typelevel/log4cats/experimental/slf4j/Slf4jEncoding.scala) | Native logger lookup at binding; native enablement; immutable encoded values; a fresh builder and scoped MDC per delivery. |
| OTel | [OtelBackend](module/runtime-otel/src/main/scala/org/typelevel/log4cats/experimental/otel/OtelBackend.scala), [AnyValueEncoding](module/otel/src/main/scala/org/typelevel/log4cats/experimental/otel/AnyValueEncoding.scala) | Safe lookup in F; Dispatcher for unsafe lookup; producer telemetry context retained across delivery. |
| Circe | [CirceBackend](module/circe/src/main/scala/org/typelevel/log4cats/experimental/circe/CirceBackend.scala), [JsonEncoding](module/circe/src/main/scala/org/typelevel/log4cats/experimental/circe/JsonEncoding.scala) | Direct interpretation into Json and an application-supplied effectful sink. |
| Console | [ConsoleBackend](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/console/ConsoleBackend.scala), [ConsoleRenderer](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/console/ConsoleRenderer.scala) | Reference output using the [Value](module/core/src/main/scala/org/typelevel/log4cats/experimental/value/Value.scala) representation. |

The runtime's early SLF4J acceptance check receives effective static and ambient MDC through
`BoundBackend.acceptsWithContext`, matching direct contextual acceptance. Per-call MDC is not
available until the writer runs; filters requiring it cannot participate in this early gate.
Structured fields never become MDC implicitly. Console renders separate `mdc` and `fields`
objects; Circe and custom sinks receive both channels in `EncodedEvent`.

## Preparation, failure, and ownership

The supplied backends encode each accepted, field-filtered output separately. Field-rejected
outputs do not encode. A preparation failure prevents delivery to all outputs.

[DeliveryFailurePolicy](module/runtime/src/main/scala/org/typelevel/log4cats/experimental/pipeline/DeliveryFailurePolicy.scala)
applies around each prepared output's delivery, before outputs are combined or queued. The default
reports through the `Console[F]` supplied to runtime resource construction, to stderr, and continues
without retrying. The runtime retains its `Sync[F]` requirement. Later sibling
outputs run even when an earlier output fails; later records remain deliverable. Direct SLF4J and
OTel factories are unchanged.

`Pipeline.withDeliveryFailurePolicy(DeliveryFailurePolicy.reportAndContinue(report))` supplies an
effectful error reporter. Its `DeliveryFailure` carries level, scope, event name, and original error.
A failed reporter falls back to Console diagnostics once; a failed fallback is swallowed. Reporting
runs within delivery, so slow reporters can delay the queue or synchronous caller. Reporters must
not log through the same failing pipeline. Cancellation is not recovered.

All delivery policies report and continue. A failed output does not skip later siblings or discard
queued records. Earlier emissions and admissions remain; admission across queues is not atomic.
The policy nearest an output overrides enclosing defaults; consecutive calls use the last policy.

Capture, native enablement, callback, encoding, and filter errors remain strict, as do acquisition
and queue lifecycle errors. Validation can also fail during construction, for example for an empty
logger name or invalid field name. The supplied encodings reject null text and byte arrays;
causes must be nonnull. This change does not introduce null compatibility or catch errors while
strict logging arguments are evaluated.

Events retain domain values by reference until encoding. The supplied encodings snapshot byte
arrays during encoding, but throwable references and captured backend states are not deep copies.
A writer uses the last body, cause, or duplicate field value and is sealed after its callback,
including when the callback fails. Use it only within that callback, synchronously.
Empty values, bytes, and nonfinite numbers have backend-specific representations; conversion is
not always reversible.

The runtime samples one producer timestamp after the initial filters. OTel uses it as event time
and samples observation time on delivery. The SLF4J adapter leaves timestamps to the provider, so
queued delivery does not preserve the producer timestamp in native SLF4J output.

Each pipeline resource acquisition acquires the configured outputs and creates its queues and workers.
Named loggers share those outputs and workers.
Keep factories, loggers, and deliveries within the owning resource lifetime. SLF4J and OTel
providers remain application-owned. Direct OTel lookup in either factory can block; use an
`IO.blocking` boundary if it must be called from IO.

## Async delivery

`async(capacity)` adds a bounded queue. `(a ++ b).async(n)` queues the combined delivery;
`a.async(n) ++ b` queues only `a` and delivers `b` on the caller's path. See
[PipelineTopologySuite](module/runtime/src/test/scala/org/typelevel/log4cats/experimental/pipeline/PipelineTopologySuite.scala)
for placement tests. [AsyncOutputExample](module/runtime-example/src/main/scala/org/typelevel/log4cats/experimental/AsyncOutputExample.scala)
shows a single async output and explicit flush.

Admission waits when the queue is full and acknowledges enqueueing, not completed delivery.
Cancellation racing admission can leave a record queued. `flush` waits for work admitted before
its barrier; it does not flush an OTel exporter or native appender.

Recoverable sink errors are handled per output and do not fail the worker. A barrier waits for
delivery and its error reporting; recovered errors do not cause `flush` or release to fail. Error
history is reported through the configured handler rather than retained by the queue.

The queue also reports and recovers ordinary errors escaping a queued action, then processes its
remaining work. Only worker termination (including cancellation) or shutdown closes admission.
Unexpected worker termination is reported by later admissions, flush, and resource release. Normal shutdown closes admission and drains queued work
before releasing outputs. A shutdown timeout cancels the worker, but cannot force an uncancelable
backend action to stop.

## Tests to read

| Question | Suite |
| --- | --- |
| Do context sources compose and capture lazily? | [ContextSourceSuite](module/core/src/test/scala/org/typelevel/log4cats/experimental/context/ContextSourceSuite.scala) |
| Does SLF4J work without the runtime? | [Slf4jFactorySuite](module/slf4j/src/test/scala/consumer/Slf4jFactorySuite.scala) |
| Does direct OTel lookup/context handling work? | [OtelFactorySuite](module/otel/src/test/scala/org/typelevel/log4cats/experimental/otel/OtelFactorySuite.scala) |
| Do helpers work from consumer code and defer messages/events? | [InlineFrontendSuite](module/core/src/test/scala/consumer/InlineFrontendSuite.scala) |
| Are factories and bound handles separate, with typed context? | [BackendBindingSuite](module/runtime/src/test/scala/consumer/BackendBindingSuite.scala) |
| Do names share acquired resources? | [LoggerFactorySuite](module/runtime/src/test/scala/org/typelevel/log4cats/experimental/pipeline/LoggerFactorySuite.scala) |
| How do filters and context compose? | [PipelineSuite](module/runtime/src/test/scala/org/typelevel/log4cats/experimental/pipeline/PipelineSuite.scala), [ContextSuite](module/runtime/src/test/scala/org/typelevel/log4cats/experimental/context/ContextSuite.scala) |
| What survives preparation and replay? | [DeliveryContractSuite](module/runtime/src/test/scala/org/typelevel/log4cats/experimental/pipeline/DeliveryContractSuite.scala), [BackendPreparationSuite](module/runtime/src/test/scala/consumer/BackendPreparationSuite.scala) |
| Do error policies isolate siblings and preserve cancellation? | [DeliveryFailurePolicySuite](module/runtime/src/test/scala/org/typelevel/log4cats/experimental/pipeline/DeliveryFailurePolicySuite.scala), [DeliveryContractSuite](module/runtime/src/test/scala/org/typelevel/log4cats/experimental/pipeline/DeliveryContractSuite.scala) |
| How do queue placement and failure affect delivery? | [PipelineTopologySuite](module/runtime/src/test/scala/org/typelevel/log4cats/experimental/pipeline/PipelineTopologySuite.scala), [AsyncDeliveryQueueSuite](module/runtime/src/test/scala/org/typelevel/log4cats/experimental/pipeline/internal/AsyncDeliveryQueueSuite.scala) |
| Do native adapters preserve the record? | [Slf4jBackendSuite](module/runtime-slf4j/src/test/scala/consumer/Slf4jBackendSuite.scala), [OtelPipelineSuite](module/runtime-otel/src/test/scala/org/typelevel/log4cats/experimental/otel/OtelPipelineSuite.scala), [CirceSuite](module/circe/src/test/scala/org/typelevel/log4cats/experimental/circe/CirceSuite.scala) |

## Questions for review

1. Is the single logging method, writer callback, and encoding model useful for libraries and applications?
2. Does separating core, direct integrations, and runtime keep ordinary usage small?
3. Which runtime rules should be configurable: timestamps, context precedence, preparation failures,
   or async delivery?
4. Are the macro/inline helpers worth keeping? Are shared record capture and typed backend state
   useful boundaries?
5. Should context scoping remain a separate capability, or be available through a logger?
