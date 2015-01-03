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

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.{Lock, ReentrantLock}
import scala.collection.immutable.Queue
import scala.concurrent.{Promise, Future}
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

  trait ChannelInternal[T] {
    def put(value: T, req: Request[Boolean]): Boolean
    def take(req: Request[Option[T]]): Boolean
    def close()
  }

  class UnbufferedChannel[T] extends ChannelInternal[T] {
    val mutex = new ReentrantLock
    var closed = false
    var takeq = Queue[Request[Option[T]]]()
    var putq = Queue[(Request[Boolean], T)]()

    def put(value: T, req: Request[Boolean]): Boolean = {
      // if waiting takes, pass on, complete
      // if buffer has room, to buffer, complete
      // else queue in puts
      mutex.lock
      cleanup
      try {
        if (closed) {
          req.lock
          try {
            req.setInactive
            req.promise.success(false) }
          finally req.unlock
          true
        }
        else if (takeq.isEmpty) {
          if (putq.size >= ChannelWaitingRequestLimit) {
            req.promise.failure(new IllegalStateException)
            false
          } else {
            putq = putq.enqueue((req, value))
            false
          }
        }
        // TODO guard against too many in queue
        else {
          val (t, q) = takeq.dequeue
          takeq = q
          t.lock
          try {
            t.setInactive
            t.promise.success(Some(value))
          }
          finally t.unlock
          req.lock
          try {
            req.setInactive
            req.promise.success(true)
          }
          finally req.unlock
          true
        }
      }
      finally mutex.unlock
    }

    def take(req: Request[Option[T]]): Boolean = {
      // if data in buffer, take, complete, and if puts, add to buffer and complete
      // if no buffer, take from takes
      // else queue take
      mutex.lock
      cleanup
      try {
        if (closed) {
          req.lock
          try {
            if (req.isActive) {
              req.setInactive
              req.promise.success(None)
              true
            }
            else false
          } finally req.unlock
        }
        else if (putq.isEmpty) {
          if (takeq.size >= ChannelWaitingRequestLimit) {
            req.promise.failure(new IllegalStateException)
            false
          } else {
            takeq = takeq.enqueue(req)
            false
          }
        }
        // TODO guard against too many in queue
        else {
          val ((p, v), q) = putq.dequeue
          putq = q
          p.lock
          try {
            p.setInactive
            p.promise.success(true)
          } finally p.unlock
          req.lock
          try {
            req.setInactive
            req.promise.success(Some(v))
          } finally req.unlock
          true
        }
      }
      finally mutex.unlock
    }

    def close() {
      mutex.lock
      cleanup
      try {
        if (!closed) {
          closed = true
          for (t <- takeq.toSeq) t.promise.success(None)
          for ((p, _) <- putq.toSeq) p.promise.success(true)
          takeq = Queue()
          putq = Queue()
        }
      }
      finally mutex.unlock
    }

    def cleanup() {
      takeq = takeq.filter(req => req.isActive)
      putq = putq.filter(item => item._1.isActive)
    }
  }

}
