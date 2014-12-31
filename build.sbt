lazy val root = (project in file(".")).
  settings(
    name := "asynq",
    version := "1.0",
    scalaVersion := "2.11.4"
  )

libraryDependencies += "org.scala-lang.modules" %% "scala-async" % "0.9.2"
