val Scala212          = "2.12.21"
val Scala213          = "2.13.18"
val Scala3            = "3.3.9"
val allScalaVersions  = Seq(Scala212, Scala213, Scala3)
val otelScalaVersions = Seq(Scala213, Scala3)

ThisBuild / organization       := "io.github.irevive"
ThisBuild / version            := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion       := Scala213
ThisBuild / crossScalaVersions := allScalaVersions
ThisBuild / licenses           := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / scalacOptions ++= {
  if (scalaBinaryVersion.value == "3") Seq("-no-indent")
  else Seq("-language:experimental.macros")
}

val catsEffectVersion      = "3.7.1"
val catsCoreVersion        = "2.13.0"
val otel4sVersion          = "1.2.0"
val munitCatsEffectVersion = "2.2.1"
val slf4jVersion           = "2.0.18"
val logbackVersion         = "1.5.38"

lazy val testSettings = Seq(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test,
    "org.typelevel" %% "cats-effect"       % catsEffectVersion      % Test
  )
)

lazy val root = project
  .in(file("."))
  .aggregate(core, runtime, circe, slf4j, otel, runtimeSlf4j, runtimeOtel, example, runtimeExample)
  .settings(
    name               := "log4cats-rework-poc",
    crossScalaVersions := Nil,
    publish / skip     := true
  )

lazy val core = project
  .in(file("module/core"))
  .settings(testSettings)
  .settings(
    name                := "log4cats-rework-core",
    libraryDependencies += "org.typelevel" %% "cats-core" % catsCoreVersion,
    libraryDependencies ++= {
      if (scalaBinaryVersion.value == "3") Nil
      else Seq("org.scala-lang" % "scala-reflect" % scalaVersion.value % Provided)
    }
  )

lazy val runtime = project
  .in(file("module/runtime"))
  .dependsOn(core)
  .settings(testSettings)
  .settings(
    name := "log4cats-rework-runtime",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.typelevel" %% "cats-mtl"    % "1.6.0",
      "org.typelevel" %% "vault"       % "3.7.0"
    )
  )

lazy val circe = project
  .in(file("module/circe"))
  .dependsOn(runtime)
  .settings(testSettings)
  .settings(
    name                := "log4cats-rework-circe",
    libraryDependencies += "io.circe" %% "circe-core" % "0.14.10"
  )

lazy val slf4j = project
  .in(file("module/slf4j"))
  .dependsOn(core)
  .settings(testSettings)
  .settings(
    name := "log4cats-rework-slf4j",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"     % catsEffectVersion,
      "org.slf4j"      % "slf4j-api"       % slf4jVersion,
      "ch.qos.logback" % "logback-classic" % logbackVersion % Test
    )
  )

lazy val otel = project
  .in(file("module/otel"))
  .dependsOn(core)
  .settings(testSettings)
  .settings(
    name               := "log4cats-rework-otel",
    crossScalaVersions := otelScalaVersions,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"             % catsEffectVersion,
      "org.typelevel" %% "otel4s-core-logs"        % otel4sVersion,
      "org.typelevel" %% "otel4s-oteljava"         % otel4sVersion % Test,
      "org.typelevel" %% "otel4s-oteljava-testkit" % otel4sVersion % Test
    )
  )

lazy val example = project
  .in(file("module/example"))
  .dependsOn(slf4j)
  .settings(
    name                 := "log4cats-rework-example",
    publish / skip       := true,
    Compile / run / fork := true,
    Compile / mainClass  := Some("org.typelevel.log4cats.experimental.Slf4jExample"),
    libraryDependencies  += "ch.qos.logback" % "logback-classic" % logbackVersion % Runtime
  )

lazy val runtimeExample = project
  .in(file("module/runtime-example"))
  .dependsOn(runtimeSlf4j, runtimeOtel)
  .settings(
    name                 := "log4cats-rework-runtime-example",
    crossScalaVersions   := otelScalaVersions,
    publish / skip       := true,
    Compile / run / fork := true,
    Compile / mainClass  := Some("org.typelevel.log4cats.experimental.MixedOutputExample"),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "otel4s-oteljava"         % otel4sVersion,
      "org.typelevel" %% "otel4s-oteljava-testkit" % otel4sVersion,
      "ch.qos.logback" % "logback-classic"         % logbackVersion % Runtime
    )
  )

lazy val runtimeSlf4j = project
  .in(file("module/runtime-slf4j"))
  .dependsOn(runtime, slf4j)
  .settings(testSettings)
  .settings(
    name                := "log4cats-rework-runtime-slf4j",
    libraryDependencies += "ch.qos.logback" % "logback-classic" % logbackVersion % Test
  )

lazy val runtimeOtel = project
  .in(file("module/runtime-otel"))
  .dependsOn(runtime, otel)
  .settings(testSettings)
  .settings(
    name               := "log4cats-rework-runtime-otel",
    crossScalaVersions := otelScalaVersions,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "otel4s-oteljava"         % otel4sVersion % Test,
      "org.typelevel" %% "otel4s-oteljava-testkit" % otel4sVersion % Test
    )
  )

addCommandAlias(
  "ci",
  ";fmtCheck;+test;example/run;runtimeExample/run;runtimeExample/runMain org.typelevel.log4cats.experimental.AsyncOutputExample;runtimeExample/runMain org.typelevel.log4cats.experimental.PreparedDeliveryExample"
)

addCommandAlias("fmt", ";+scalafmtAll;scalafmtSbt")
addCommandAlias("fmtCheck", ";+scalafmtCheckAll;scalafmtSbtCheck")
