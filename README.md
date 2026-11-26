# log4cats rework proof of concept

An executable proof of concept for discussing a new log4cats model.

The core model has a single logging operation, a writer callback for constructing records, and
encoders that interpret structured values directly into each backend's representation. SLF4J is the
primary integration; OTel exercises structured values and telemetry context handling.

Start with [Slf4jExample](module/example/src/main/scala/org/typelevel/log4cats/experimental/Slf4jExample.scala).
Ordinary SLF4J usage needs no pipeline or logging runtime:

```scala
for {
  factory <- Slf4j.factory[IO]
  logger  <- factory.fromName("checkout")
  _       <- logger.info("ready")
} yield ()
```

`Slf4j.factory` also accepts an explicit `org.slf4j.ILoggerFactory`. Named loggers retain native
handles and respect native level configuration. SLF4J owns timestamps and output configuration.
`Otel.resource(provider)` supplies a direct OTel factory; its Resource owns the Dispatcher for unsafe
lookup, while the supplied provider remains application-owned.

A separate optional runtime explores filters, context sources, fan-out, prepared records, and async
delivery. See [MixedOutputExample](module/runtime-example/src/main/scala/org/typelevel/log4cats/experimental/MixedOutputExample.scala).
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

| Module | Purpose | Scala |
| --- | --- | --- |
| `module/core` | Logger/factory API, events, encoding, reference values | 2.12, 2.13, 3 |
| `module/slf4j` | Direct SLF4J factory and encoding | 2.12, 2.13, 3 |
| `module/otel` | Direct otel4s factory and encoding | 2.13, 3 |
| `module/runtime` | Optional pipeline, context, queues, preparation, console output | 2.12, 2.13, 3 |
| `module/runtime-slf4j` | SLF4J adapter for the optional runtime | 2.12, 2.13, 3 |
| `module/runtime-otel` | otel4s adapter for the optional runtime | 2.13, 3 |
| `module/circe` | JSON encoding and runtime output adapter | 2.12, 2.13, 3 |
| `module/example` | Direct SLF4J example | 2.12, 2.13, 3 |
| `module/runtime-example` | Runtime composition examples | 2.13, 3 |

Versions are pinned in [build.sbt](build.sbt). Run `sbt fmt` to format or `sbt fmtCheck` to check.

## Review scope

Feedback is wanted on the API, encoding model, direct integrations, and optional runtime boundary.
The runtime's timestamp policy and the API for ambient context scoping remain open design questions.
