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

import scala.collection.immutable.Queue

trait Buffer[T] {
  def isFull: Boolean
  def add(value: T)
  def remove(): T
  def size(): Int
}

class FixedBuffer[T](max: Int) extends Buffer[T] {
  var q = Queue[T]()
  def isFull: Boolean = {
    size >= max
  }
  def add(value: T) {
    q = q.enqueue(value)
  }
  def remove(): T = {
    val (value, tail) = q.dequeue
    q = tail
    value
  }
  def size(): Int = q.size
}
