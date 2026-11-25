# log4cats rework proof of concept

An executable proof of concept for discussing a new log4cats model.

The model has a single logging operation, a writer callback for constructing records, and encoders
that interpret structured values directly into each backend's representation. Applications compose
destinations, filters, context sources, and optional async delivery. SLF4J is the primary backend;
OTel and Circe exercise other representations and integration requirements.

Start with [MixedOutputExample](module/example/src/main/scala/org/typelevel/log4cats/experimental/MixedOutputExample.scala).
Its checkout component takes a `Logger[IO]`; application configuration supplies request fields and
routes records to SLF4J/Logback and an in-memory OTel provider.
The [source map](SOURCE_MAP.md) follows a log call through the implementation and identifies review questions.

## Run

Use JDK 17 or later and sbt, from the repository root:

```sh
sbt 'example/run'
sbt 'example/runMain org.typelevel.log4cats.experimental.AsyncOutputExample'
sbt 'example/runMain org.typelevel.log4cats.experimental.PreparedDeliveryExample'
sbt test       # Scala 2.13
sbt +test      # all supported Scala versions
sbt ci         # formatting, cross-version tests, and all three examples
```

Build destinations with `Pipeline.fromBackend(backendResource)` and combine them with `++`.
`Pipeline.resource` returns `Resource[F, LoggerFactory[F]]`. Named loggers share the acquired
outputs and workers. `Pipeline.runtimeResource` exposes `LoggingRuntime[F]` for explicit flushing
and prepared delivery. Keep logger use and delivery within the owning Resource lifetime.

| Module | Purpose | Scala |
| --- | --- | --- |
| `module/core` | API, encoding, context, pipeline, console output | 2.12, 2.13, 3 |
| `module/slf4j` | SLF4J 2 adapter; Logback in tests | 2.12, 2.13, 3 |
| `module/otel` | otel4s adapter | 2.13, 3 |
| `module/circe` | JSON encoding and output adapter | 2.12, 2.13, 3 |
| `module/example` | Runnable examples | 2.13, 3 |

Versions are pinned in [build.sbt](build.sbt). Run `sbt fmt` to format or `sbt fmtCheck` to check.

## Review scope

Feedback is wanted on the API, encoding model, backend binding, and pipeline semantics.
Timestamp ownership and the boundary between core and integrations remain open design questions.
