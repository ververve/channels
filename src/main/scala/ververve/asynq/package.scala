package ververve

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.async.Async.{async, await}

package object asynq {

  def pauseInc(value: Int): Future[Int] = {
    Future {
      val pause = 5000
      println("inc will take " + pause)
      Thread.sleep(pause)
      value + 1
    }
  }


  def main(args: Array[String]) {
    println("start")
    async {
      var i = 1
      while (true) {
        i = await(pauseInc(i))
        println("got " + i)
      }
    }
    println("end")
  }

}
