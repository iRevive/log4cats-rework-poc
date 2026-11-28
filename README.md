# log4cats rework proof of concept

This project tests a possible new log4cats API. It includes direct SLF4J and OpenTelemetry (OTel)
integrations, plus an optional runtime for combining outputs and delivering records asynchronously.

`Logger` has one abstract method: `log(level, eventName, write)`. The `write` callback builds a record.
Encoders convert domain values into each backend's representation without an intermediate value tree.

## Direct logging

Start with [Slf4jExample](module/example/src/main/scala/org/typelevel/log4cats/experimental/Slf4jExample.scala).
The SLF4J factory needs no pipeline or runtime resource:

```scala
import cats.effect.IO
import org.typelevel.log4cats.experimental.slf4j.Slf4j

val factory = Slf4j.factory[IO]

for {
  logger <- factory.fromName("checkout")
  _      <- logger.info("ready")
} yield ()
```

`Slf4j.factory` returns `LoggerFactory[F]` and accepts a nested `Slf4j.Config` for the native factory and acceptance policy.
Each logger retains its native SLF4J handle and checks the provider's level configuration when the
logging effect runs. SLF4J controls timestamps and output configuration. Native checks and emission
run in `F.delay` on the calling thread.

Both direct integrations and the runtime use these lookup methods:

| Method | Returns |
| --- | --- |
| `fromName` / `fromClass` | `F[Logger[F]]` |
| `getLoggerFromName` / `getLoggerFromClass` | `Logger[F]` |

### SLF4J acceptance checks

SLF4J defaults to `Slf4j.Enablement.MdcAware`: read logical context and install its MDC for the
native check. To avoid those operations on disabled direct calls:

```scala
val factory = Slf4j.factory[IO](source, Slf4j.Config(enablement = Slf4j.Enablement.Native))
```

`Native` calls the provider with existing thread state. It does not read logical context or install
MDC during checks; enabled logging still captures context for emission. Provider filters may still
run, so this is not a provider-independent threshold lookup. Use the default when native filtering
needs logical MDC. SLF4J's early native check does not receive event-name metadata or per-call fields.

Choosing Native for a pipeline backend changes its native gate; it does not skip the pipeline's
shared context capture. Configure it with `Slf4jBackend.acquire[IO](Slf4j.Config(enablement = Slf4j.Enablement.Native))`.

### Diagnostic context and structured fields

For ambient enrichment, pass a core `ContextSource[F]`. Diagnostic context and structured fields
are separate channels:

```scala
val source = ContextSource.const[IO](LogContext(
  mdc = Mdc("request.id" -> "req-42"),
  fields = Fields(Field("service", "checkout"))
))
val factory = Slf4j.factory[IO](source)
// Event.message("done").withMdc("request.id", "req-43").withField("attempt", 2)
```

`Mdc` holds nonnull strings and maps only to native MDC (e.g. Logback `%X{request.id}`).
`Fields` retains typed, potentially nested values and maps only to native key-value pairs.
The same key can exist in both channels; no automatic mirroring occurs. Event-name metadata is
emitted as an `event.name` key-value pair and does not populate or overwrite MDC.

`ContextSource(effect)` reads `F[LogContext]`. Use `ContextSource.fromFields(effect)` for an
existing `F[Fields]`, or `ContextSource.fromMdc(effect)` for `F[Mdc]`.
`ContextSource.const` accepts either `LogContext` or `Fields`; `fromAsk` adapts `Ask[F, Fields]`,
and `fromContextAsk` adapts `Ask[F, LogContext]`. Combine sources with `++`: reads run left to
right and later values win independently in each channel. Each reader runs once per logging execution.
Per-call values override ambient values within their channel. SLF4J installs ambient MDC for early
native enablement; per-call MDC is available during emission, after record construction.
By default, `Slf4j.MdcPolicy.Isolate` hides unrelated thread MDC during MDC-aware checks and
emission. For legacy integrations, opt into overlay:

```scala
val config = Slf4j.Config(mdcPolicy = Slf4j.MdcPolicy.Overlay)
val factory = Slf4j.factory[IO](source, config)
```

Overlay retains existing thread entries; logical MDC wins collisions, with per-call MDC winning
over ambient MDC during emission. Both policies restore the complete previous thread MDC after
success or failure. `Enablement.Native` still checks existing thread state without installing logical
MDC; the MDC policy applies when emitting.

The same config works with `Slf4jBackend.acquire[IO](config)`. Overlay uses thread MDC at each
check or delivery, including prepared replay and async delivery; it does not capture producer
thread MDC. Use explicit logical MDC to carry values across fibers and queues. Overlay can include
stale or unrelated entries from the current thread, so isolation remains the default.

The optional runtime's `ScopedContext` supplies fiber-local scopes for both channels via
`withMdc`, `withFields`, or `scope(LogContext(...))`, and exposes a `source` for either direct factories
or pipelines. `LoggingContext.withContext` / `withMdc` use an existing `Local[F, LogContext]`.
The field-only `ScopedFields` and `LoggingContext.withFields` helpers remain available.

### OpenTelemetry

`Otel.resource(provider)` returns `Resource[F, LoggerFactory[F]]`.
`Otel.resource(provider, source)` accepts the same two-channel context source, read after native
enablement. Per-call values override source values within each channel. The logger captures native
telemetry context separately. The resource owns a Dispatcher for direct lookup; the application
owns the provider. Prefer `fromName` / `fromClass`: direct OTel lookup blocks while running the
provider effect.

OTel merges both channels into attributes at the adapter boundary. MDC values remain strings;
structured fields win same-key collisions. `Otel.resource(provider, source, MdcMapping.Prefixed("mdc."))`
prefixes MDC attribute keys instead. Structured fields also win explicit prefixed-key collisions,
and generated exception attributes win reserved exception keys when a cause is present.
`OtelBackend.resource(provider, MdcMapping.Prefixed("mdc."))` configures the runtime adapter.

## Optional runtime

The runtime adds filters, context sources on individual branches, multiple outputs, prepared
records, and bounded async queues. See
[MixedOutputExample](module/runtime-example/src/main/scala/org/typelevel/log4cats/experimental/MixedOutputExample.scala)
and the [source map](SOURCE_MAP.md) for the logging sequence and delivery rules.

Build an output with `Pipeline.fromBackend(backendResource)` and combine outputs with `++`.
`Pipeline.resource` returns `Resource[F, LoggerFactory[F]]`. `Pipeline.runtimeResource` returns a
`LoggingRuntime[F]`, which also provides `flush` and `prepare`. Use factories, loggers, and prepared
deliveries within their owning resource's lifetime.

Each backend retains its own typed context for prepared and queued delivery. Generic records contain
metadata and payload, with no native context. `OtelBackend.resource(provider, fields)` can also turn
captured native context into fields shared with other outputs.

`withFields` and `withMdc` supply independent pipeline defaults. `filterFields` sees structured
fields; `filterContext` sees both effective channels. Console output has separate `mdc` and `fields`
members; Circe and custom sinks receive both in `EncodedEvent`.

### Delivery errors

Pipeline outputs report sink delivery errors to stderr through `cats.effect.std.Console` and
continue by default. Runtime resource construction takes an implicit `Console[F]` (available
for `IO` and supported transformers). A failed output does not skip later siblings, and an async
queue keeps processing later records. Failed records are not retried.

To use your own reporter:

```scala
import org.typelevel.log4cats.experimental.pipeline.DeliveryFailurePolicy

val policy = DeliveryFailurePolicy.reportAndContinue[IO](failure => reportToMonitoring(failure))
val outputs = (primary ++ telemetry).withDeliveryFailurePolicy(policy).async(256)
```

A `DeliveryFailure` contains the level, logger scope, event name, and original error. Reporters run
as part of delivery, so they should finish promptly and must not log through the same failing
pipeline. If a reporter fails, diagnostics fall back to Console's stderr once, without invoking
that reporter again. A fallback failure is swallowed.

All delivery policies report and continue. Policies set on individual branches override enclosing
policies. Ordinary delivery errors never permanently fail the queue or discard its pending work.

Capture, enablement, callback, validation, and encoding errors still propagate. Preparation failure
still prevents all selected outputs from delivering. Cancellation, closed queues, worker termination,
and shutdown timeouts retain their existing behavior. After recovered delivery errors, `flush` and
release succeed; the reporter is the error channel, and no historical error list is retained.
These policies apply to pipelines only. Direct SLF4J and OTel factories keep their existing behavior.

## Run

Use sbt from the repository root. CI uses JDK 17.

```sh
sbt 'example/run'       # direct SLF4J example
sbt 'runtimeExample/run'
sbt 'runtimeExample/runMain org.typelevel.log4cats.experimental.AsyncOutputExample'
sbt 'runtimeExample/runMain org.typelevel.log4cats.experimental.PreparedDeliveryExample'
sbt test               # default Scala version: 2.13
sbt +test              # all configured Scala versions for each module
sbt ci                 # formatting checks, cross-version tests, and all four examples
```

Run `sbt fmt` to format Scala and build files, or `sbt fmtCheck` to check their formatting.
Dependency and Scala versions are set in [build.sbt](build.sbt).

## Modules

All modules target the JVM.

| Module | Purpose | Scala |
| --- | --- | --- |
| `module/core` | Logger/factory API, events, MDC and structured context sources, encoding, reference values | 2.12, 2.13, 3 |
| `module/slf4j` | Direct SLF4J factory and encoding | 2.12, 2.13, 3 |
| `module/otel` | Direct otel4s factory and encoding | 2.13, 3 |
| `module/runtime` | Pipelines, context scoping helpers, queues, preparation, console output | 2.12, 2.13, 3 |
| `module/runtime-slf4j` | SLF4J adapter for pipelines | 2.12, 2.13, 3 |
| `module/runtime-otel` | otel4s adapter for pipelines | 2.13, 3 |
| `module/circe` | JSON encoding and runtime output adapter | 2.12, 2.13, 3 |
| `module/example` | Direct SLF4J example | 2.12, 2.13, 3 |
| `module/runtime-example` | Runtime examples | 2.13, 3 |

`slf4j` and `otel` depend on core. `runtime-slf4j` depends on runtime and slf4j;
`runtime-otel` depends on runtime and otel. The direct integrations do not depend on runtime.
Core uses Cats and cats-mtl, with cats-effect in tests only. The integrations and runtime use
cats-effect in production. The circe module depends on runtime.

## Review scope

Feedback is welcome on the API, encoding model, direct integrations, and runtime boundary.
Timestamp policy and ambient context scoping are still open design questions. The
[source map](SOURCE_MAP.md#questions-for-review) lists specific questions.
