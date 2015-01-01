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
    def put(value: T): Future[Unit]
    def take(): Future[T]
  }

  object Channel {
    def apply[T]() = new UnbufferedChannel[T]
  }

  class UnbufferedChannel[T] extends Channel[T] {
    val mutex = new ReentrantLock
    val takeq = new mutable.Queue[Promise[T]]
    val putq = new mutable.Queue[(Promise[Unit], T)]

    def put(value: T): Future[Unit] = {
      // if waiting takes, pass on, complete
      // if buffer has room, to buffer, complete
      // else queue in puts
      mutex.lock
      try {
        if (takeq.isEmpty) {
          val putp = Promise[Unit]
          putq.enqueue((putp, value))
          putp.future
        }
        // TODO guard against too many in queue
        else {
          val takep = takeq.dequeue
          takep.success(value)
          Future.successful()
        }
      }
      finally mutex.unlock
    }

    def take(): Future[T] = {
      // if data in buffer, take, complete, and if puts, add to buffer and complete
      // if no buffer, take from takes
      // else queue take
      mutex.lock
      try {
        if (putq.isEmpty) {
          val takep = Promise[T]
          takeq.enqueue(takep)
          takep.future
        }
        // TODO guard against too many in queue
        else {
          val (putp, v) = putq.dequeue()
          putp.success()
          Future.successful(v)
        }
      }
      finally mutex.unlock
    }
  }

  def main(args: Array[String]) {
    println("start")
    val c = Channel[Int]()
    c.put(-7)
    c.put(-4)
    async {
      while (true) {
        val i = await(c.take)
        println("got " + i)
      }
    }
    println("end")
    for (i <- Range(1,10)) c.put(i)
  }

}
