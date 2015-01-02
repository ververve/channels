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

import java.util.concurrent.locks.{Lock, ReentrantLock}
import scala.collection._
import scala.concurrent.{Promise, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.async.Async.{async, await}

package object asynq {

  trait Channel[T] {
    def put(value: T): Future[Boolean]
    def take(): Future[Option[T]]
    def close()
  }

  def channel[T]() = new Channel[T] {
    val internal = new UnbufferedChannel[T]

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
  }

  trait Request[R] {
    def isActive(): Boolean
    def lockId: Long
    def lock: Lock
    def promise: Promise[R]
  }

  class SingleRequest[R] extends Request[R] {
    def isActive() = true
    def lockId = 0
    def lock = NoopLock
    val promise = Promise[R]
  }

  trait ChannelInternal[T] {
    def put(value: T, req: Request[Boolean])
    def take(req: Request[Option[T]])
    def close()
  }

  class UnbufferedChannel[T] extends ChannelInternal[T] {
    val mutex = new ReentrantLock
    var closed = false
    val takeq = new mutable.Queue[Request[Option[T]]]
    val putq = new mutable.Queue[(Request[Boolean], T)]

    def put(value: T, req: Request[Boolean]) {
      // if waiting takes, pass on, complete
      // if buffer has room, to buffer, complete
      // else queue in puts
      //if (value == null) throw new IllegalArgumentException
      mutex.lock
      try {
        if (closed) {
          req.promise.success(false)
        }
        else if (takeq.isEmpty) {
          putq.enqueue((req, value))
        }
        // TODO guard against too many in queue
        else {
          val t = takeq.dequeue
          t.promise.success(Some(value))
          req.promise.success(true)
        }
      }
      finally mutex.unlock
    }

    def take(req: Request[Option[T]]) = {
      // if data in buffer, take, complete, and if puts, add to buffer and complete
      // if no buffer, take from takes
      // else queue take
      mutex.lock
      try {
        if (closed) {
          req.promise.success(None)
        }
        else if (putq.isEmpty) {
          takeq.enqueue(req)
        }
        // TODO guard against too many in queue
        else {
          val (p, v) = putq.dequeue()
          p.promise.success(true)
          req.promise.success(Some(v))
        }
      }
      finally mutex.unlock
    }

    def close() {
      mutex.lock
      try {
        if (!closed) {
          closed = true
          for (t <- takeq.dequeueAll(_ => true)) t.promise.success(None)
          for ((p, _) <- putq.dequeueAll(_ => true)) p.promise.success(true)
        }
      }
      finally mutex.unlock
    }
  }

  def main(args: Array[String]) {
    println("start")
    val c = channel[Int]()
    c.put(-7)
    c.put(-4)
    async {
      while (true) {
        val i = await(c.take)
        println("got " + i)
      }
    }
    for (i <- Range(1,10)) c.put(i)
    println("end")
  }

}
