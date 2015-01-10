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

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.{Lock, ReentrantLock}
import scala.concurrent.{ExecutionContext, Promise, Future, Await}

private[channels] trait Request[R] {
  def isActive: Boolean
  def setInactive
  def lockId: Long
  def lock
  def unlock
  def promise: Promise[R]
}

private[channels] class SingleRequest[R] extends Request[R] {
  val isActive = true
  def setInactive {}
  val lockId: Long = 0
  def lock = {}
  def unlock = {}
  val promise = Promise[R]
}

private[channels] class SharedRequestFlag {
  lazy val lock = new ReentrantLock
  val active = new AtomicBoolean(true)
  val lockId: Long = 1
}

private[channels] class SharedRequest[R](flag: SharedRequestFlag) extends Request[R] {
  def isActive = flag.active.get
  def setInactive { flag.active.set(false) }
  val lockId = flag.lockId
  def lock { flag.lock.lock() }
  def unlock { flag.lock.unlock() }
  val promise = Promise[R]
}
