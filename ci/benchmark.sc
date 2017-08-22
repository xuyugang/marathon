#!/usr/bin/env amm

import ammonite.ops._
import ammonite.ops.ImplicitWd._
import scala.concurrent.duration._
import scala.sys.process.Process
import scalaj.http._
import upickle._

// val StartPattern = s"^.*Received new deployment plan $deploymentId, no conflicts.*$$".r
// val StopPattern = s"^.*Deployment $deploymentId.*of / finished.*$$".r

// TODO: Run with JMH?
@main
def run(runSpecPath: String): Unit = {
  // TODO: Start ZooKeeper in memory
  // TODO: Start Marathon simulation

  println(s"Create deploytment for $runSpecPath")
  val runSpecFile = new java.io.File(runSpecPath)
  val runSpecData = read! Path(runSpecFile.toPath().toAbsolutePath())
  val response = Http("http://localhost:8080/v2/groups")
    .timeout(5.seconds.toMillis.toInt, 10.seconds.toMillis.toInt)
    .put(runSpecData)
    .asString
    .throwError

  val deploymentId = upickle.json.read(response.body).apply("deploymentId")
  println(s"Put Deployment: $deploymentId")
}
