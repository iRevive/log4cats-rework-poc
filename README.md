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

`Slf4j.factory` returns `LoggerFactory[F]` and can take an explicit `org.slf4j.ILoggerFactory`.
Each logger retains its native SLF4J handle and checks the provider's level configuration when the
logging effect runs. SLF4J controls timestamps and output configuration. Native checks and emission
run in `F.delay` on the calling thread.

Both direct integrations and the runtime use these lookup methods:

| Method | Returns |
| --- | --- |
| `fromName` / `fromClass` | `F[Logger[F]]` |
| `getLoggerFromName` / `getLoggerFromClass` | `Logger[F]` |

### Context fields

Pass a `ContextSource[F]` to read fields when logging. For example,
`Slf4j.factory[IO](ContextSource(local.get))` reads an application-owned `IOLocal[Fields]`.
Sources can wrap an effect (`ContextSource(effect)`), fixed fields (`ContextSource.const(fields)`),
or a Cats MTL reader (`ContextSource.fromAsk(ask)`).

Combine sources with `requestSource ++ telemetrySource`. They read left to right, and later fields
win on duplicate names. Direct SLF4J logging reads the combined source once per execution, even if
the level is disabled. Per-call fields override source fields.

SLF4J emits fields as typed key-value pairs and also copies their string representations into MDC,
so Logback patterns such as `%X{request.id}` can display them. During native checks and emission,
this MDC replaces the thread's existing entries; the previous MDC is restored afterward.

### OpenTelemetry

`Otel.resource(provider)` returns `Resource[F, LoggerFactory[F]]`.
`Otel.resource(provider, source)` also accepts a `ContextSource[F]`; it reads fields after native
enablement, with per-call fields taking precedence. The logger captures native telemetry context
separately. The resource owns a Dispatcher for direct lookup; the application owns the provider.
Prefer `fromName` / `fromClass`: direct OTel lookup blocks while running the provider effect.

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
| `module/core` | Logger/factory API, events, context sources, encoding, reference values | 2.12, 2.13, 3 |
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
