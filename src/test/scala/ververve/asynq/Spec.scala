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
import org.scalatest.time.SpanSugar._
import org.scalatest.concurrent.PatienceConfiguration._
import scala.concurrent.ExecutionContext.Implicits.global

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

  it should "not complete pending puts" in {
    val c = channel[Int]()
    val res = c.put(56)
    res.isCompleted should equal (false)
    c.close()
    res.isCompleted should equal (false)
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

  it should "allow buffered or awaiting puts to be taken" in {
    val c = channel[Int](3)
    c.put(8)
    c.put(31)
    c.put(2)
    c.put(4) // not complete
    c.close()
    c.put(23) // ignored
    c.take().futureValue should be (Some(8))
    c.take().futureValue should be (Some(31))
    c.take().futureValue should be (Some(2))
    c.take().futureValue should be (Some(4))
    c.take().futureValue should be (None)
    c.put(99)
    c.take().futureValue should be (None)
  }

  "An Alts" should "select the first result if possible immediately, take option" in {
    val c1 = channel[String]()
    val c2 = channel[String]()
    c1.put("rabbit")
    c2.put("noise")
    val res = alts(c1, c2)
    res.futureValue should equal (Some("rabbit"))
  }

  it should "select the first result if possible immediately, put option" in {
    val c1 = channel[String]()
    val c2 = channel[String]()
    val take1 = c1.take
    val take2 = c2.take
    val res = alts("rabbit" -> c1, "noise" -> c2)
    res.futureValue should equal (true)
    take1.futureValue should equal (Some("rabbit"))
    take2.isReadyWithin(1000 millis) should be (false)
  }

  it should "select in order of argument the first result that is possible immediately" in {
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

  it should "only select one alternative when available, take option" in {
    // implicit val defaultPatience = PatienceConfig(timeout = Span(2, Seconds), interval = Span(5, Millis))
    val c1 = channel[Int]()
    val c2 = channel[Int]()
    val res = alts(c1, c2)
    res.isCompleted should be (false)
    val put2 = c2.put(2)
    val put1 = c1.put(1)
    res.futureValue(Timeout(5000 millis)) should equal (Some(2))
    put2.isCompleted should equal (true)
    put1.isCompleted should equal (false)
  }

  it should "only select one alternative when available, put option" in {
    // implicit val defaultPatience = PatienceConfig(timeout = Span(2, Seconds), interval = Span(5, Millis))
    val c1 = channel[Int]()
    val c2 = channel[Int]()
    val res = alts(1 -> c1, 2 -> c2)
    res.isCompleted should be (false)
    val take2 = c2.take
    val take1 = c1.take
    res.futureValue(Timeout(5000 millis)) should equal (true)
    take2.isCompleted should equal (true)
    take1.isCompleted should equal (false)
  }

  "A Buffered Channel" should "allow puts to be buffered" in {
    val c = channel[Int](3)
    c.put(1) should be ('completed)
    c.put(2) should be ('completed)
    c.put(3) should be ('completed)
    val put4 = c.put(4)
    val put5 = c.put(5)
    put4 should not be ('completed)
    put5 should not be ('completed)

    c.take.futureValue should be (Some(1))
    put4 should be ('completed)
    put5 should not be ('completed)

    c.take.futureValue should be (Some(2))
    c.take.futureValue should be (Some(3))
    c.take.futureValue should be (Some(4))
    c.take.futureValue should be (Some(5))
    c.take should not be ('completed)
  }

  it should "allow takes to proceed without buffering" in {
    val c = channel[String](3)
    val take = c.take
    c.put("Joy")
    take.futureValue should be (Some("Joy"))
  }
}
