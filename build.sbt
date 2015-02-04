lazy val root = (project in file(".")).
  settings(
    name := "channels",
    version := "0.1",
    scalaVersion := "2.11.4"
  )

libraryDependencies += "org.scala-lang.modules" %% "scala-async" % "0.9.3" % "test"

libraryDependencies += "org.scalatest" % "scalatest_2.11" % "2.2.1" % "test"

homepage := Some(url("https://github.com/ververve/channels"))

licenses := Seq("Eclipse Public License - v 1.0" -> url("http://www.eclipse.org/legal/epl-v10.html"))

publishMavenStyle := true

publishArtifact in Test := false

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
  else Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

pomIncludeRepository := { _ => false }

pomExtra := (
  <scm>
    <url>git@github.com:ververve/channels.git</url>
    <connection>scm:git:git@github.com:ververve/channels.git</connection>
  </scm>
  <developers>
    <developer>
      <id>scott-abernethy</id>
      <name>Scott Abernethy</name>
      <url>http://ververve.com</url>
    </developer>
  </developers>)
