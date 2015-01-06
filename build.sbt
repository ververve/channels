lazy val root = (project in file(".")).
  settings(
    name := "asynq",
    version := "1.0",
    scalaVersion := "2.11.4"
  )

// libraryDependencies += "org.scala-lang.modules" %% "scala-async" % "0.9.2"

libraryDependencies += "org.scala-lang.modules" %% "scala-async" % "0.9.3-SNAPSHOT"

libraryDependencies += "org.scalatest" % "scalatest_2.11" % "2.2.1" % "test"
