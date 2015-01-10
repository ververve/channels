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
import scala.collection.mutable.Queue
import scala.concurrent.{ExecutionContext, Promise, Future, Await}

package object channels {

  val ChannelWaitingRequestLimit = 512

  trait Channel[T] {
    def put(value: T): Future[Boolean]
    def take(): Future[Option[T]]
    def close()
    private[channels] def put(value: T, req: Request[Boolean]): Boolean
    private[channels] def take(req: Request[Option[T]]): Boolean
  }

  def channel[T]() = {
    new ChannelInternal[T](null)
  }

  def channel[T](buffer: Int) = {
    val bufferImpl =
      if (buffer > 0) new FixedBuffer[T](buffer)
      else null
    new ChannelInternal[T](bufferImpl)
  }

  sealed trait AltOption[T]
  case class PutAlt[T](c: Channel[T], value: T) extends AltOption[T]
  case class TakeAlt[T](c: Channel[T]) extends AltOption[T]

  implicit def chanTakeAltOption[T](c: Channel[T]): TakeAlt[T] = TakeAlt(c)
  implicit def tuplePutAltOption[T](put: Tuple2[T, Channel[T]]): PutAlt[T] = PutAlt(put._2, put._1)

  def alts[T](options: AltOption[T]*)(implicit executor: ExecutionContext): Future[Any] = {
    val flag = new SharedRequestFlag
    val init: Either[List[Future[Any]], Future[Any]] = Left(Nil)
    val future = options.foldLeft(init){ (acc, option) =>
      acc match {
        case Left(fs) =>
          val (f, complete) = option match {
            case PutAlt(c, v) =>
              val req = new SharedRequest[Boolean](flag)
              (req.promise.future, c.put(v, req))
            case TakeAlt(c) =>
              val req = new SharedRequest[Option[T]](flag)
              (req.promise.future, c.take(req))
          }
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

  private[channels] def withLock[W](lock: Lock)(block: => W): W = {
    lock.lock
    try block
    finally lock.unlock
  }

  private[channels] def withLock[W,R](req: Request[R])(block: Request[R] => W): W = {
    req.lock
    try block(req)
    finally req.unlock
  }

  private[channels] def succeed[R](req: Request[R], result: => R): Boolean = {
    if (req.isActive) {
      req.setInactive
      req.promise.success(result)
      true
    } else {
      false
    }
  }

  class ChannelInternal[T](buffer: Buffer[T]) extends Channel[T] {
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
            } else if (putq.size >= ChannelWaitingRequestLimit) {
              req.promise.failure(new IllegalStateException)
              false
            } else {
              putq.enqueue((req, value))
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
              } else if (takeq.size >= ChannelWaitingRequestLimit) {
                req.promise.failure(new IllegalStateException)
              } else {
                takeq.enqueue(req)
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

    def take(): Future[Option[T]] = {
      val req = new SingleRequest[Option[T]]
      take(req)
      req.promise.future
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

  }

}
