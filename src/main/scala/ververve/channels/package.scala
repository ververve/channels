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

  /**
   * Create a timeout channel, that will close after the specified duration.
   */
  def timeout[T](duration: Duration): Channel[T] = {
    val c = channel[T]()
    TimeoutDaemon.add(c, duration)
    c
  }

  sealed trait AltOption[+T] {
    private[channels] def action(flag: SharedRequestFlag)(implicit executor: ExecutionContext): (Boolean, Future[_])
  }
  case class PutAlt[T, Y <: T](c: Channel[Y], value: Y) extends AltOption[T] {
    private[channels] def action(flag: SharedRequestFlag)(implicit executor: ExecutionContext) = {
      val req = new SharedRequest[Boolean](flag)
      // TODO this map via executor adds a slow down just to add the channel. Lame. Do in request instead?
      val reqF = req.promise.future.map((c, _))
      (c.put(value, req), reqF)
    }
  }
  case class TakeAlt[T, Y <: T](c: Channel[Y]) extends AltOption[T] {
    private[channels] def action(flag: SharedRequestFlag)(implicit executor: ExecutionContext) = {
      val req = new SharedRequest[Option[Y]](flag)
      // TODO this map via executor adds a slow down just to add the channel. Lame. Do in request instead?
      val reqF = req.promise.future.map((c, _))
      (c.take(req), reqF)
    }
  }

  implicit def chanTakeAltOption[T, Y <: T](c: Channel[Y]): TakeAlt[T, Y] = TakeAlt(c)
  implicit def tuplePutAltOption[T, Y <: T](put: Tuple2[Y, Channel[Y]]): PutAlt[T, Y] = PutAlt(put._2, put._1)

  /**
   * Completes at most one of the given Channel operations (puts or takes).
   */
  def alts[T](options: AltOption[T]*)(implicit executor: ExecutionContext): Future[Any] = {
    val flag = new SharedRequestFlag
    val init: Either[List[Future[Any]], Future[Any]] = Left(Nil)
    val future = options.foldLeft(init){ (acc, option) =>
      acc match {
        case Left(fs) =>
          val (complete, f) = option.action(flag)
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

  // sealed trait AltResult[+T, +P]
  // case class PutResult[T, P, P_ <: P](c: Channel[P_], res: Boolean) extends AltResult[T, P]
  // case class TakeResult[T, P, T_ <: T](c: Channel[T_], res: Option[T]) extends AltResult[T, P]

  // trait AltBuilder[+T, +P] {
  //   def take[T_ <: T](channel: Channel[T_]): AltBuilder[T, P]
  //   def put[P_ <: P](channel: Channel[P_], value: P_): AltBuilder[T, P]
  //   def select(): Future[AltResult[T, P]]
  // }

  // class PendingAltBuilder[T,P](
  //   flag: SharedRequestFlag,
  //   futures: List[Future[Option[T]]],
  //   executor: ExecutionContext)
  //   extends AltBuilder[T,P] {
  //   def or[V >: T](channel: Channel[V]): AltBuilder[V] = {
  //     val req = new SharedRequest[Option[V]](flag)
  //     if (channel.take(req)) new CompleteAltBuilder(req.promise.future)
  //     else new PendingAltBuilder(flag, req.promise.future :: futures, executor)
  //   }
  //   def select(): Future[Option[T]] = {
  //     Future.firstCompletedOf(futures)(executor)
  //   }
  // }

  // class CompleteAltBuilder[T,P](result: Future[Option[T]]) extends AltBuilder[T,P] {
  //   def or[V >: T](channel: Channel[V]): AltBuilder[V] = {
  //     this
  //   }
  //   def select(): Future[Option[T]] = {
  //     result
  //   }
  // }

  // def alt[T](channel: Channel[T])(implicit executor: ExecutionContext): AltBuilder[T] = {
  //   val flag = new SharedRequestFlag
  //   val req = new SharedRequest[Option[T]](flag)
  //   if (channel.take(req)) new CompleteAltBuilder(req.promise.future)
  //   else new PendingAltBuilder(flag, req.promise.future :: Nil, executor)
  // }

}
