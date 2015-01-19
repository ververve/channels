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

import scala.concurrent.{ExecutionContext, Promise, Future, Await}
import scala.concurrent.duration.Duration

package object channels {

  val ChannelWaitingRequestLimit = 512

  /**
   * Create an unbuffered channel.
   */
  def channel[T](): Channel[T] = {
    new ChannelInternal[T](null)
  }

  /**
   * Create a channel with a fixed size buffer.
   */
  def channel[T](bufferSize: Int): Channel[T] = {
    val bufferImpl =
      if (bufferSize > 0) new FixedBuffer[T](bufferSize)
      else null
    new ChannelInternal[T](bufferImpl)
  }

  def timeout[T](duration: Duration): Channel[T] = {
    val c = channel[T]()
    TimeoutDaemon.add(c, duration)
    c
  }

  sealed trait AltOption[T]
  case class PutAlt[T](c: Channel[T], value: T) extends AltOption[T]
  case class TakeAlt[T](c: Channel[T]) extends AltOption[T]

  implicit def chanTakeAltOption[T](c: Channel[T]): TakeAlt[T] = TakeAlt(c)
  implicit def tuplePutAltOption[T](put: Tuple2[T, Channel[T]]): PutAlt[T] = PutAlt(put._2, put._1)

  /**
   * Alts.
   */
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

}
