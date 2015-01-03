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

package ververve.asynq

import org.scalatest._
import org.scalatest.concurrent._

class Spec extends FlatSpec with Matchers with ScalaFutures {

  "A Channel" should "not put until a taker arrives" in {
    val c = channel[String]()
    val put1 = c.put("apple")
    val put2 = c.put("banana")
    put1.isCompleted should equal (false)
    put2.isCompleted should equal (false)
    c.take
    put1.isCompleted should equal (true)
    put1.futureValue should equal (true)
    put2.isCompleted should equal (false)
  }

  it should "take puts in order" in {
    val c = channel[Long]()
    val take1 = c.take
    val take2 = c.take
    c.put(9)
    c.put(2)
    take1.futureValue should equal (Some(9))
    take2.futureValue should equal (Some(2))
  }

  "A closed Channel" should "complete all pending takes with none" in {
    val c = channel[Int]()
    val res = c.take
    res.isCompleted should equal (false)
    c.close()
    res.isCompleted should equal (true)
    res.futureValue should equal (None)
  }

  it should "complete all pending puts with true" in {
    val c = channel[Int]()
    val res = c.put(56)
    res.isCompleted should equal (false)
    c.close()
    res.isCompleted should equal (true)
    res.futureValue should equal (true)
  }

  it should "complete subsequent takes with none" in {
    val c = channel[Int]()
    c.close()
    c.take().futureValue should be (None)
  }

  it should "complete subsequent puts with false" in {
    val c = channel[Int]()
    c.close()
    c.put(88).futureValue should be (false)
  }

  "An Alts" should "select the first result if possible immediately" in {
    val c1 = channel[String]()
    val c2 = channel[String]()
    c1.put("rabbit")
    c2.put("noise")
    val res = alts(c1, c2)
    res.futureValue should equal (Some("rabbit"))
  }

  it should "select the in order of argument the first result that is possible immediately" in {
    val c1 = channel[String]()
    val c2 = channel[String]()
    val c3 = channel[String]()
    c2.put("noise")
    c2.put("silence")
    c3.put("tree")
    val res = alts(c1, c2, c3)
    res.futureValue should equal (Some("noise"))
    c2.take.futureValue should equal (Some("silence"))
    c3.take.futureValue should equal (Some("tree"))
  }

  it should "only select one alternative" in {
    val c1 = channel[Int]()
    val c2 = channel[Int]()
    val res = alts(c1, c2)
    res.isCompleted should be (false)
    val put2 = c2.put(2)
    val put1 = c1.put(1)
    res.futureValue should equal (Some(2))
    put2.isCompleted should equal (true)
    put1.isCompleted should equal (false)
  }
}
