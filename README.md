# Async-Channels

Idiomatic Scala port of [Clojure core.async](https://github.com/clojure/core.async).

Channels has no external dependencies, though it is recommended to use Channels with [Scala Async](https://github.com/scala/async) blocks.

## Quick start

Add a dependency:

```scala
// SBT
libraryDependencies += "ververve" %% "channels" % "0.1"
```

Create your first `channel`:

```scala
import ververve.channels._

val c = channel[String]()
c.put("Hello")
assert(c.take_! == Some("Hello"))
c.close
```

### Using with Scala Async

Additionally add the following dependency:

```scala
// SBT
libraryDependencies += "org.scala-lang.modules" %% "scala-async" % "0.9.3"
```

```scala
import ExecutionContext.Implicits.global
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

## License

Released under the Eclipse Public License v1.0.

Derived from Clojure core.async - Copyright © 2013 Rich Hickey and contributors.
