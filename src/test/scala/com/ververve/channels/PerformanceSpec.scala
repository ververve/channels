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

package com.ververve.channels

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.{Lock, ReentrantLock}
import scala.collection.mutable.Queue
import scala.concurrent.{Promise, Future, Await}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.async.Async.{async, await}
import org.scalatest._
import org.scalatest.concurrent._

class PerformanceSpec extends FlatSpec with Matchers with ScalaFutures {
  "Throughput" should "be high" in {
    // akka-like throughput test
    val machines = 8
    val repeat = 16000000L
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
    (throughput > 1000000) should be (true)
  }

  "Sequence test" should "work fast" in {
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
    last.take.futureValue should be (Some(length + 1))
    val end = System.currentTimeMillis
    val duration = end - start
    println(s"Sequence test length $length took duration $duration msec")
    (duration < 1000) should be (true)
  }
}
