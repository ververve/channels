package ververve

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.async.Async.{async, await}

package object asynq {

  def work(): Future[String] = {
    Future {
      Thread.sleep(5000)
      println("done")
      "done"
    }
  }


  def main(args: Array[String]) {
    println("start")
    async {
      val res = await(work())
      println("got " + res)
    }
    println("end")
  }

}
