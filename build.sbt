val scala3Version = "3.6.2"

lazy val compileFlags: Seq[String] =
  Seq(
    "-feature",
    "-Xkind-projector:underscores",
    "-Wvalue-discard",
    "-Wunused:all",
    "-Wunused:unsafe-warn-patvars",
    "-source:future"
  )

lazy val deps: List[ModuleID] = List(
  "dev.zio" %% "zio"         % "2.1.14",
  "dev.zio" %% "zio-streams" % "2.1.14"
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
