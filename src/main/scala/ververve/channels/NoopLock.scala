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

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.{Condition, Lock, ReentrantLock}

object NoopLock extends Lock {
  def lock() {}
  def lockInterruptibly(): Unit = ???
  def newCondition(): Condition = ???
  def tryLock(): Boolean = ???
  def tryLock(time: Long, unit: TimeUnit): Boolean = ???
  def unlock() {}
}
