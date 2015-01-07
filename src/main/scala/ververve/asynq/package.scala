/*
 * Copyright 2015 Scott Abernethy (github @scott-abernethy).
 * Released under the Eclipse Public License v1.0.
 *
 * This program is free software: you can use it, redistribute it and/or modify
 * it under the terms of the Eclipse Public License v1.0. Use of this software
 * in any fashion constitutes your acceptance of this license.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * license for more details.
 *
 * You should have received a copy of the Eclipse Public License v1.0 along with
 * this program. If not, see <http://opensource.org/licenses/eclipse-1.0.php>.
 */

package ververve

// import java.util.LinkedList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.{Lock, ReentrantLock}
import scala.collection.immutable.Queue
import scala.concurrent.{Promise, Future, Await}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.async.Async.{async, await}

package object asynq {

  val ChannelWaitingRequestLimit = 512

  trait Channel[T] {
    def put(value: T): Future[Boolean]
    def take(): Future[Option[T]]
    def close()

    // TODO hide
    def put(value: T, req: Request[Boolean]): Boolean
    def take(req: Request[Option[T]]): Boolean
  }

  def channel[T]() = createChannel[T](null)

  def channel[T](buffer: Int) = if (buffer > 0) createChannel(new FixedBuffer[T](buffer)) else createChannel[T](null)

  def createChannel[T](buffer: Buffer[T]) = new Channel[T] {
    val internal = new ChannelInternal[T](buffer)

    def put(value: T): Future[Boolean] = {
      val req = new SingleRequest[Boolean]
      internal.put(value, req)
      req.promise.future
    }

    def take(): Future[Option[T]] = {
      val req = new SingleRequest[Option[T]]
      internal.take(req)
      req.promise.future
    }

    def close() {
      internal.close()
    }

    // TODO hide
    def put(value: T, req: Request[Boolean]): Boolean = {
      internal.put(value, req)
    }

    def take(req: Request[Option[T]]): Boolean = {
      internal.take(req)
    }
  }

  def alts[T](channels: Channel[T]*): Future[Option[T]] = {
    val flag = new SharedRequestFlag
    val init: Either[List[Future[Option[T]]], Future[Option[T]]] = Left(Nil)
    val future = channels.foldLeft(init){ (acc, c) =>
      acc match {
        case Left(fs) =>
          val req = new SharedRequest[Option[T]](flag)
          val complete = c.take(req)
          val f = req.promise.future
          if (complete) Right(f)
          else Left(f :: fs)
        case f @ Right(_) => f
      }
    }
    future match {
      case Left(fs) => Future.firstCompletedOf(fs)
      case Right(f) => f
    }
  }

  trait Request[R] {
    def isActive: Boolean
    def setInactive
    def lockId: Long
    def lock
    def unlock
    def promise: Promise[R]
  }

  class SingleRequest[R] extends Request[R] {
    val isActive = true
    def setInactive {}
    val lockId: Long = 0
    def lock = {}
    def unlock = {}
    val promise = Promise[R]
  }

  class SharedRequestFlag {
    lazy val lock = new ReentrantLock
    val active = new AtomicBoolean(true)
    val lockId: Long = 1
  }

  class SharedRequest[R](flag: SharedRequestFlag) extends Request[R] {
    def isActive = flag.active.get
    def setInactive { flag.active.set(false) }
    val lockId = flag.lockId
    def lock { flag.lock.lock() }
    def unlock { flag.lock.unlock() }
    val promise = Promise[R]
  }

  def withLock[W](lock: Lock)(block: => W): W = {
    lock.lock
    try block
    finally lock.unlock
  }
  def withLock[W,R](req: Request[R])(block: Request[R] => W): W = {
    req.lock
    try block(req)
    finally req.unlock
  }
  def succeed[R](req: Request[R], result: => R): Boolean = {
    if (req.isActive) {
      req.setInactive
      req.promise.success(result)
      true
    } else {
      false
    }
  }

  class ChannelInternal[T](buffer: Buffer[T]) {
    val mutex = new ReentrantLock
    var closed = false
    var takeq = Queue[Request[Option[T]]]()
    var putq = Queue[(Request[Boolean], T)]()

    def put(value: T, req: Request[Boolean]): Boolean = {
      withLock(mutex){
        cleanup
        if (closed) {
          withLock(req)(succeed(_, false))
        }
        else if (takeq.isEmpty) {
          if (buffer != null && !buffer.isFull) {
            withLock(req){
              buffer.add(value)
              succeed(_, true)
            }
          } else if (putq.size >= ChannelWaitingRequestLimit) {
            req.promise.failure(new IllegalStateException)
            false
          } else {
            putq = putq.enqueue((req, value))
            false
          }
        }
        else {
          val (t, q) = takeq.dequeue
          takeq = q
          withLock(t)(succeed(_, Some(value)))
          withLock(req)(succeed(_, true))
        }
      }
    }

    def take(req: Request[Option[T]]): Boolean = {
      withLock(mutex){
        cleanup
        if (closed) {
          withLock(req)(succeed(_, None))
        } else if (buffer != null && buffer.size > 0) {
          val res = withLock(req)(succeed(_, unbuffer))
          while (!buffer.isFull && !putq.isEmpty) {
            dequeuePut match {
              case Some(v) => buffer.add(v)
              case None =>
            }
          }
          res
        } else {
          var res: Option[T] = None
          while (!putq.isEmpty && !res.isDefined) {
            res = dequeuePut
          }
          res match {
            case Some(v) =>
              withLock(req)(succeed(_, Some(v)))
            case None =>
              if (takeq.size >= ChannelWaitingRequestLimit) {
                req.promise.failure(new IllegalStateException)
              } else {
                takeq = takeq.enqueue(req)
              }
              false
          }
        }
      }
    }

    def close() {
      withLock(mutex){
        cleanup
        if (!closed) {
          closed = true
          for (t <- takeq.toSeq) t.promise.success(None)
          for ((p, _) <- putq.toSeq) p.promise.success(true)
          takeq = Queue()
          putq = Queue()
        }
      }
    }

    def cleanup() {
      takeq = takeq.filter(req => req.isActive)
      putq = putq.filter(item => item._1.isActive)
    }

    def unbuffer(): Option[T] = Some(buffer.remove)

    def dequeuePut(): Option[T] = {
      val ((p, v), q) = putq.dequeue
      putq = q
      if (withLock(p)(succeed(_, true))) Some(v)
      else None
    }

  }

  def main(args: Array[String]) {
    // throughputTest
    sequenceTest
  }

  def throughputTest {
    // akka-like throughput test
    val machines = 8
    val repeat = 40000000L
    val repeatPerMachine = repeat / machines
    val bandwidth = 9
    val buffered = 10
    case object Msg
    val doneSignal = new CountDownLatch(machines)

    val start = System.currentTimeMillis

    for (i <- 0 until machines) {
      val dest = channel[AnyRef](buffered)
      val reply = channel[AnyRef](buffered)

      // destination
      async {
        while (true) {
          await(dest.take) match {
            case Some(Msg) => reply.put(Msg)
            case _ =>
          }
        }
      }

      // client
      async {
        var sent = 0L
        var received = 0L
        for (i <- 0 until bandwidth) {
          dest.put(Msg)
          sent += 1
        }
        while (received < repeatPerMachine) {
          await(reply.take) match {
            case Some(Msg) =>
              received += 1
              if (sent < repeatPerMachine) {
                dest.put(Msg)
                sent += 1
              }
            case _ =>
          }
        }
        doneSignal.countDown
      }
    }

    doneSignal.await()
    val end = System.currentTimeMillis
    val duration = end - start
    val throughput = (repeat / (duration / 1000.0)).intValue
    println(s"Test took $duration msec ($throughput msg/sec) for $repeat messages with bandwidth of $bandwidth on $machines vmachines")
  }

  def sequenceTest {
    // sequence test
    val length = 100000
    val start = System.currentTimeMillis
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
    val res = Await.result(last.take, scala.concurrent.duration.Duration("15 seconds"))
    val end = System.currentTimeMillis
    val duration = end - start
    println(s"Sequence test length $length took duration $duration msec, got $res")
  }

}
