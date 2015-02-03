# Async Channels

An idiomatic Scala port of [Clojure core.async](https://github.com/clojure/core.async).

Channels has no external dependencies, though it is intended to be used with [Scala Async](https://github.com/scala/async) blocks.

## Quick start

Add SBT dependencies:

```scala
// For Scala 2.11.x
scalaVersion := "2.11.4"

libraryDependencies += "ververve" %% "channels" % "0.1"

// Optional (for async/await style)
libraryDependencies += "org.scala-lang.modules" %% "scala-async" % "0.9.3"
```

Create your first `Channel`:

```scala
import scala.concurrent.ExecutionContext.Implicits.global
import scala.async.Async.{async, await}
import ververve.channels._

val c = channel[String]()

async {
	val res = await(c.take)
	println(res)
}

c.put("Hello")
c.close
```

Here we create a `Channel` that can accept and deal out `String` values. In an `async` block we wait for a future value to take from the `Channel`. Outside the `async` block we then put the value `"Hello"`, which allows the `async` take to complete. Finally we close the `Channel`.

## License

Released under the Eclipse Public License v1.0.
