val scala3Version = "3.3.4"

lazy val compileFlags: Seq[String] =
  Seq(
    "-feature",
    "-Ykind-projector:underscores",
    "-Wvalue-discard",
    "-Wunused:all",
    "-Wunused:unsafe-warn-patvars",
    "-source:future"
  )

lazy val deps: List[ModuleID] = List(
  "dev.zio" %% "zio"         % "2.1.14",
  "dev.zio" %% "zio-streams" % "2.1.14",
  "dev.zio" %% "zio-prelude" % "1.0.0-RC37",
  "dev.zio" %% "zio-logging" % "2.4.0"
)

lazy val root = project
  .in(file("."))
  .settings(
    name         := "zio-swim",
    version      := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,
    javacOptions ++= Seq("-source", "21", "-target", "21"),
    scalacOptions ++= compileFlags,
    libraryDependencies ++= deps,
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test
  )
