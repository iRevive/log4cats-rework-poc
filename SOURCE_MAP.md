# Source map

Start with [Slf4jExample](module/example/src/main/scala/org/typelevel/log4cats/experimental/Slf4jExample.scala)
for direct SLF4J logging. It has no runtime dependency.
[MixedOutputExample](module/runtime-example/src/main/scala/org/typelevel/log4cats/experimental/MixedOutputExample.scala)
uses the optional runtime to send records to SLF4J and OTel with different level thresholds.

## Module boundaries

| Module | Responsibility |
| --- | --- |
| `core` | Logger/factory API, events, field sources, encoding, reference values, and internal record capture. Uses Cats and cats-mtl; cats-effect is a test dependency only. |
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
runs these steps for each logging execution:

1. Read context fields in the calling fiber.
2. Encode them and install their MDC strings for the native level check.
3. If enabled, invoke the writer once and merge context fields with call fields.
4. Encode the record and emit through a fresh SLF4J builder with the merged MDC.

Disabled calls still read and encode context; they skip the writer and call-field encoding.
Enabled calls can encode context fields twice, for the check and for emission. Call fields override
context fields. Event-name metadata overrides a field named `event.name`.

Fields are emitted as typed key-value pairs and mirrored into MDC with `String.valueOf` (`null`
becomes `"null"`). Native checks and emission each save, replace, and restore thread MDC in a
`finally` block. Existing thread entries are hidden during those actions. Both actions run in
`F.delay`, without a blocking-pool boundary, so providers and appenders run on the calling thread.
SLF4J supplies the timestamp; this path reads no clock.

## Direct OTel logging

[Otel.resource](module/otel/src/main/scala/org/typelevel/log4cats/experimental/otel/Otel.scala)
returns `Resource[F, LoggerFactory[F]]`. It owns a Dispatcher for direct lookup and leaves the
provider application-owned. Effectful lookup uses the caller's effect. Direct lookup runs the
provider effect through `Dispatcher.unsafeRunSync` and can block; prefer `fromName` / `fromClass`.

[OtelLogger](module/otel/src/main/scala/org/typelevel/log4cats/experimental/otel/OtelLogger.scala)
runs these steps for each logging execution:

1. Sample event time and capture native telemetry context.
2. Check native enablement using that context, severity, and event name.
3. If enabled, read the optional field source and invoke the writer once.
4. Merge source fields with call fields, encode, and emit using the captured telemetry context.

Call fields override source fields. Observation time is sampled during emission. A cause supplies
`exception.type`, `exception.message`, and `exception.stacktrace`, overriding fields with those
names. Event names use native OTel event-name metadata.

## Context sources and scoping

`ContextSource(effect)` wraps an effect that reads `Fields`. `const(fields)` supplies fixed fields,
`empty[F]` supplies none, and `fromAsk(ask)` adapts `Ask[F, Fields]`.
Combining sources with `a ++ b` reads left to right, with later fields winning on duplicate names.
A failed read stops later reads. `map` transforms fields on each read.

Sources only read fields. Applications own the carriers they read, such as an `IOLocal` or an
MTL environment. SLF4J does not extract telemetry fields automatically.
For scoping, runtime provides `LoggingContext.withFields` for `Local[F, Fields]`, or
`ScopedFields.scope` for an `IOLocal[Fields]`. Connect these to a logger with
`ContextSource.fromAsk(local)` or `ScopedFields.contextSource(scoped)`. These sources work with
the direct factories as well as pipelines, though the scoping helpers depend on runtime.
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

Each logging execution follows this sequence, stopping if a step fails:

```text
minimum-level and metadata filters
  → timestamp, root/source fields, and typed backend context capture
  → merge projected backend fields in output order
  → backend acceptance checks
  → writer callback once, if any backend accepts
  → for each accepted output: merge fields, apply field filters, prepare if kept
  → deliver in pipeline order, directly or through queues
```

If all outputs fail the minimum-level or metadata filters, the runtime reads no clock or context
and skips the callback. Field filters run after the callback because they need its fields.

`AccumulatingRecordWriter.capture` creates `RecordData`. `CapturedEvent` adds metadata and merged
fields for one output. Neither `CapturedEvent` nor `EncodedEvent` holds native context.
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

Fields merge in this order, with later values winning:

1. Static defaults from `Pipeline.withFields`.
2. Root context, then sources attached along the output's branch from outer to inner.
3. Fields projected from captured backend context, in output order.
4. Fields written by the call.

Outer static defaults override inner defaults; repeated `withFields` calls use the later values.
A source attached above `a ++ b` is read once if either branch survives the initial filters.
Attaching the same source object separately to each branch reads it twice if both survive.
Source fields stay on their branches.

Every output surviving the initial filters captures its backend context before any native
acceptance check, provided earlier captures succeed. Projected fields from all those outputs are
shared, including fields from outputs that later reject the record. The native states remain
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

The runtime's SLF4J acceptance check sees level and event-name metadata, but no source or call
fields. Effective fields reach MDC on delivery. Use the direct factory's context reader when
MDC-dependent enablement needs source fields.

## Preparation, failure, and ownership

The supplied backends encode each accepted, field-filtered output separately. Field-rejected
outputs do not encode. A preparation failure prevents delivery to all outputs. Once delivery
starts, it is sequential and stops on the first failure: earlier emissions or queue admissions
remain. Admission across multiple queues is not atomic.

Logging errors propagate to the calling effect; there is no built-in recovery policy. Validation
can also fail during construction, for example for an empty logger name or invalid field name.
The supplied encodings reject null text and byte arrays; causes must be nonnull.

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

A worker failure stops further delivery. Later admissions, flush, and resource release report the
failure; pending records are skipped. Normal shutdown closes admission and drains queued work
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
