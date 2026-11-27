# log4cats rework proof of concept

An executable proof of concept for discussing a new log4cats model.

The core model has a single logging operation, a writer callback for constructing records, and
encoders that interpret structured values directly into each backend's representation. SLF4J is the
primary integration; OTel exercises structured values and telemetry context handling.

Start with [Slf4jExample](module/example/src/main/scala/org/typelevel/log4cats/experimental/Slf4jExample.scala).
Ordinary SLF4J usage needs no pipeline or logging runtime:

```scala
val factory = Slf4j.factory[IO]

for {
  logger <- factory.fromName("checkout")
  _       <- logger.info("ready")
} yield ()
```

`Slf4j.factory` returns `LoggerFactory[F]` directly and also accepts an explicit `org.slf4j.ILoggerFactory`. Named loggers retain native
handles and respect native level configuration. SLF4J owns timestamps and output configuration.
`fromName`/`fromClass` return `F[Logger[F]]`; `getLoggerFromName`/`getLoggerFromClass` return
`Logger[F]` directly, following log4cats naming. Native checks and emission use `F.delay`, with no blocking-pool boundary.
For dynamic fields, pass a core `ContextSource[F]`, for example
`Slf4j.factory[IO](ContextSource(local.get))` with an `IOLocal[Fields]`.
Construct sources with `ContextSource(effect)`, `ContextSource.const(fields)`, or
`ContextSource.fromAsk(ask)`. Combine them with `requestSource ++ telemetrySource`: reads run left
to right and later fields win. The combined reader runs once per logging execution. Context fields
and per-call fields are emitted as structured key-value pairs and mirrored into MDC strings, supporting Logback `%X{request.id}`.
Per-call fields override context fields. Unrelated thread MDC entries are excluded during logging
and restored afterward.

`Otel.resource(provider)` supplies a direct OTel factory; its Resource owns the Dispatcher for unsafe
lookup, while the supplied provider remains application-owned. `Otel.resource(provider, source)`
also accepts the shared field-only context source.

A separate optional runtime explores filters, context-source placement, fan-out, prepared records,
and async delivery. See [MixedOutputExample](module/runtime-example/src/main/scala/org/typelevel/log4cats/experimental/MixedOutputExample.scala).
The [source map](SOURCE_MAP.md) explains both paths, module boundaries, and review questions.

## Run

Use JDK 17 or later and sbt, from the repository root:

```sh
sbt 'example/run'       # direct SLF4J usage; no runtime dependency
sbt 'runtimeExample/run'
sbt 'runtimeExample/runMain org.typelevel.log4cats.experimental.AsyncOutputExample'
sbt 'runtimeExample/runMain org.typelevel.log4cats.experimental.PreparedDeliveryExample'
sbt test       # Scala 2.13
sbt +test      # all supported Scala versions
sbt ci         # formatting, cross-version tests, and all four examples
```

For the optional runtime, build destinations with `Pipeline.fromBackend(backendResource)` and
combine them with `++`. `Pipeline.resource` returns `Resource[F, LoggerFactory[F]]`;
`Pipeline.runtimeResource` adds explicit flushing and prepared delivery. Keep use within the owning
Resource lifetime. This machinery is not a dependency of the direct SLF4J or OTel integrations.
Backends retain their own typed native context in prepared deliveries; generic records carry only
metadata and log payload. An OTel adapter may project correlation fields from its native capture
for sibling outputs with `OtelBackend.resource(provider, fields)`.

| Module | Purpose | Scala |
| --- | --- | --- |
| `module/core` | Logger/factory API, events, field context sources, encoding, reference values | 2.12, 2.13, 3 |
| `module/slf4j` | Direct SLF4J factory and encoding | 2.12, 2.13, 3 |
| `module/otel` | Direct otel4s factory and encoding | 2.13, 3 |
| `module/runtime` | Optional pipeline, context, queues, preparation, console output | 2.12, 2.13, 3 |
| `module/runtime-slf4j` | SLF4J adapter for the optional runtime | 2.12, 2.13, 3 |
| `module/runtime-otel` | otel4s adapter for the optional runtime | 2.13, 3 |
| `module/circe` | JSON encoding and runtime output adapter | 2.12, 2.13, 3 |
| `module/example` | Direct SLF4J example | 2.12, 2.13, 3 |
| `module/runtime-example` | Runtime composition examples | 2.13, 3 |

`slf4j` and `otel` depend on core and provide direct `LoggerFactory` implementations.
`runtime-slf4j` depends on runtime and slf4j; `runtime-otel` depends on runtime and otel. These
adapters reuse the native implementations and expose `Backend` values for pipeline composition.
Direct integration users do not need either runtime adapter. Core depends on Cats and cats-mtl;
cats-effect is used by the integrations and runtime. All modules currently target the JVM.

Versions are pinned in [build.sbt](build.sbt). Run `sbt fmt` to format or `sbt fmtCheck` to check.

## Review scope

Feedback is wanted on the API, encoding model, direct integrations, and optional runtime boundary.
The runtime's timestamp policy and the API for ambient context scoping remain open design questions.
