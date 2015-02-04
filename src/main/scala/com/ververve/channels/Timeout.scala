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

import scala.concurrent.{Future}
import scala.concurrent.duration.Duration
import java.util.concurrent.{DelayQueue, Delayed, TimeUnit, ConcurrentSkipListMap}

object TimeoutDaemon {
  val timeouts = new DelayQueue[TimeoutQueueEntry]
  val worker = new Thread(new Runnable{
    def run() {
      while (true) {
        val entry = timeouts.take
        entry.close
      }
    }
  })
  worker.setDaemon(true)
  worker.start()

  def add(c: Channel[_], duration: Duration) {
    val t = System.currentTimeMillis + duration.toMillis
    val entry = new TimeoutQueueEntry(c, t)
    timeouts.put(entry)
  }
}

class TimeoutQueueEntry(val channel: Channel[_], val timestamp: Long) extends Delayed {

  def getDelay(timeUnit: TimeUnit) = {
    timeUnit.convert(timestamp - System.currentTimeMillis, TimeUnit.MILLISECONDS)
  }

  def compareTo(other: Delayed) = {
    val otimestamp = other.asInstanceOf[TimeoutQueueEntry].timestamp
    if (timestamp < otimestamp) -1
    else if (timestamp == otimestamp) 0
    else 1
  }

  def close() {
    channel.close()
  }
}
