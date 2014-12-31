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
    val c = new UnbufferedChannel[Int]()
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
