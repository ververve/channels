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

package ververve.channels

import scala.collection.mutable.Queue
import scala.concurrent.{ExecutionContext, Promise, Future, Await}
import java.util.concurrent.locks.{Lock, ReentrantLock}

/**
 * Channel.
 */
trait Channel[T] {

  /**
   * Put a value into this channel.
   */
  def put(value: T): Future[Boolean]

  /**
   * Blocking put a value into this channel.
   */
  def put_!(value: T): Boolean

  /**
   * Take a value from this channel.
   */
  def take(): Future[Option[T]]

  /**
   * Blocking take a value from this channel.
   */
  def take_!(): Option[T]

  /**
   * Closes this channel. Subseqent puts will be ignored. Awaiting and buffered puts remain available to take.
   */
  def close()

  private[channels] def put(value: T, req: Request[Boolean]): Boolean

  private[channels] def take(req: Request[Option[T]]): Boolean
}

class ChannelInternal[T](buffer: Buffer[T]) extends Channel[T] {
  val blockingAtMost = scala.concurrent.duration.Duration.Inf
  val mutex = new ReentrantLock
  var closed = false
  lazy val takeq = new Queue[Request[Option[T]]]()
  lazy val putq = new Queue[(Request[Boolean], T)]()

  private[channels] def put(value: T, req: Request[Boolean]): Boolean = {
    withLock(mutex){
      if (closed) {
        withLock(req)(succeed(_, false))
      }
      else {
        var suc = false
        while (!takeq.isEmpty && !suc) {
          suc = dequeueTake(value)
        }
        if (suc) {
          withLock(req)(succeed(_, true))
        } else {
          if (buffer != null && !buffer.isFull) {
            withLock(req){
              buffer.add(value)
              succeed(_, true)
            }
          } else {
            validatePutq
            if (putq.size >= ChannelWaitingRequestLimit) {
              req.promise.failure(new IllegalStateException)
            } else {
              putq.enqueue((req, value))
            }
            false
          }
        }
      }
    }
  }

  private[channels] def take(req: Request[Option[T]]): Boolean = {
    withLock(mutex){
      if (buffer != null && buffer.size > 0) {
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
            if (closed) {
              withLock(req)(succeed(_, None))
            } else {
              validateTakeq
              if (takeq.size >= ChannelWaitingRequestLimit) {
                req.promise.failure(new IllegalStateException)
              } else {
                takeq.enqueue(req)
              }
            }
            false
        }
      }
    }
  }

  def close() {
    withLock(mutex){
      if (!closed) {
        closed = true
      }
      for (t <- takeq.toSeq; if t.isActive) t.promise.success(None)
      takeq.clear
    }
  }

  def put(value: T): Future[Boolean] = {
    val req = new SingleRequest[Boolean]
    put(value, req)
    req.promise.future
  }

  def put_!(value: T): Boolean = {
    Await.result(put(value), blockingAtMost)
  }

  def take(): Future[Option[T]] = {
    val req = new SingleRequest[Option[T]]
    take(req)
    req.promise.future
  }

  def take_!(): Option[T] = {
    Await.result(take(), blockingAtMost)
  }

  private def unbuffer(): Option[T] = Some(buffer.remove)

  private def dequeuePut(): Option[T] = {
    val (p, v) = putq.dequeue
    if (withLock(p)(succeed(_, true))) Some(v)
    else None
  }

  private def dequeueTake(v: T): Boolean = {
    val t = takeq.dequeue
    withLock(t)(succeed(_, Some(v)))
  }

  private def validatePutq() {
    putq.dequeueFirst{ i =>
      val (p, _) = i
      // lock not needed
      withLock(p)(!_.isActive)
    }
  }

  private def validateTakeq() {
    takeq.dequeueFirst{ t =>
      // lock not needed
      withLock(t)(!_.isActive)
    }
  }

  private def withLock[W](lock: Lock)(block: => W): W = {
    lock.lock
    try block
    finally lock.unlock
  }

  private def withLock[W,R](req: Request[R])(block: Request[R] => W): W = {
    req.lock
    try block(req)
    finally req.unlock
  }

  private def succeed[R](req: Request[R], result: => R): Boolean = {
    if (req.isActive) {
      req.setInactive
      req.promise.success(result)
      true
    } else {
      false
    }
  }

}
