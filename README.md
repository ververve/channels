# Channels

## Asynchronous programming with Channels in Scala

An idiomatic Scala port of [Clojure core.async](https://github.com/clojure/core.async), with facilities for asychronous programming and communication using Channels.

Channels has no external dependencies, though it is intended to be used with [Scala Async](https://github.com/scala/async) blocks.

## Quick start

Add SBT dependencies:

```scala
scalaVersion := "2.11.4"

libraryDependencies += "com.ververve" %% "channels" % "0.1"

// Optional (for async/await style)
libraryDependencies += "org.scala-lang.modules" %% "scala-async" % "0.9.3"
```

Create your first `Channel`:

```scala
import scala.concurrent.ExecutionContext.Implicits.global
import scala.async.Async.{async, await}
import com.ververve.channels._

val c = channel[String]()

async {
  val res = await(c.take)
  println(res)
}

c.put("Hello")
c.close
```

In the above we

1. Create a `Channel` that can accept and deal out `String` values.
2. In an `async` block we wait for a future value to `take` from the `Channel`.
3. Outside the `async` block we then `put` the value `"Hello"`, which allows the `async` `take` to complete.
4. Finally we close the `Channel`.

## Usage

Create an unbuffered 'rendezvous' channel that accepts `Long` type:

```scala
val c = channel[Long]()   // c: Channel[Long]
```

Create a channel with fixed size buffer:

```scala
val c = channel[Long](5)   // c: Channel[Long]
```

Close a channel with `Channel.close`:

```scala
c.close()
```

Synchronous (blocking) operations `Channel.put_!` and `Channel.take_!`:

```scala
val c = channel[String]
Future {
  c.put_!("Hello")
}
val res = c.take_!()   // res: Option[String]
assert(res == Some("Hello"))
```

Asynchronous (non-blocking) operations `Channel.put` and `Channel.take`:

```scala
val c = channel[String]
c.put("Hello")
val f = c.take()   // f: Future[Option[String]]
val res = Await.result(res, 1.second)   // res: Option[String]
assert(res == Some("Hello"))
```

Asynchronous (non-blocking) operations using `Async` blocks:

```scala
val c = channel[String]
async {
  val res = await(c.take)   // res: Option[String]
  assert(res == Some("Hello"))
}
c.put("Hello")
```

Mixing Synchronous and Asynchronous operations on the same `Channel`:

```scala
val c = channel[String]
c.put("Hello")
val res = c.take_!()   // res: Option[String]
assert(res == Some("Hello"))
```

Select the first available `Channel.take` result with `alts`:

```scala
val c1 = channel[Int]
val c2 = channel[String]
async {
  while (true) {
    val res = await(alts(c1, c2))
    println("Got" + res)
  }
}
c2.put("Hello")
c1.put(34)

// Outputs:
// > Got (ChannelInternal@7f5ff567, Some(Hello))
// > Got (ChannelInternal@7232ab12, Some(34))

```
We can even select the first available `Channel.take` or `Channel.put` with `alts`:

```scala
val c1 = channel[Int]
val c2 = channel[String]
async {
  while (true) {
    await(alts(34 -> c1, c2)) match {
      case (`c1`, _)) =>    // Put complete
      case (`c2`, res) =>   // Take result
    }
  }
}
c2.put("Hello")
assert(c1.take_! == Some(34))
```

Timeout incomplete `alts` operations using `timeout` `Channel`:

```scala
val c = channel[String]
val t = timeout[String](5.seconds)
alts_!("Hi" -> c, t)
// After 5 seconds returns (`t`, None)
```

Channels are very lightweight so you can create a lot of them very cheaply, here we create and chain together 100,000 in <100 milliseconds:

```scala
val length = 100000
val first = channel[Int]()
var last = first
for (i <- 0 until length) {
  val dest = channel[Int]()
  val src = last
  async {
    val v = await(src.take)
    dest.put(v.get + 1)
  }
  last = dest
}
first.put(1)
assert(last.take_! == Some(length + 1))
```

## License

Released under the Eclipse Public License v1.0.
