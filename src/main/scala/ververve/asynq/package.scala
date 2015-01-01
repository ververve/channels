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

import java.util.concurrent.locks.ReentrantLock
import scala.collection._
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.async.Async.{async, await}

package object asynq {

  trait Channel[T] {
    def put(value: T): Future[Boolean]
    def take(): Future[Option[T]]
    def close()
  }

  def channel[T]() = new UnbufferedChannel[T]


  class UnbufferedChannel[T] extends Channel[T] {
    val mutex = new ReentrantLock
    var closed = false
    val takeq = new mutable.Queue[Promise[Option[T]]]
    val putq = new mutable.Queue[(Promise[Boolean], T)]

    def put(value: T): Future[Boolean] = {
      // if waiting takes, pass on, complete
      // if buffer has room, to buffer, complete
      // else queue in puts
      //if (value == null) throw new IllegalArgumentException
      mutex.lock
      try {
        if (closed) {
          Future.successful(false)
        }
        else if (takeq.isEmpty) {
          val putp = Promise[Boolean]
          putq.enqueue((putp, value))
          putp.future
        }
        // TODO guard against too many in queue
        else {
          val takep = takeq.dequeue
          takep.success(Some(value))
          Future.successful(true)
        }
      }
      finally mutex.unlock
    }

    def take(): Future[Option[T]] = {
      // if data in buffer, take, complete, and if puts, add to buffer and complete
      // if no buffer, take from takes
      // else queue take
      mutex.lock
      try {
        if (closed) {
          Future.successful(None)
        }
        else if (putq.isEmpty) {
          val takep = Promise[Option[T]]
          takeq.enqueue(takep)
          takep.future
        }
        // TODO guard against too many in queue
        else {
          val (putp, v) = putq.dequeue()
          putp.success(true)
          Future.successful(Some(v))
        }
      }
      finally mutex.unlock
    }

    def close() {
      mutex.lock
      try {
        if (!closed) {
          closed = true
          for (takep <- takeq.dequeueAll(_ => true)) takep.success(None)
          for ((putp, _) <- putq.dequeueAll(_ => true)) putp.success(false)
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
