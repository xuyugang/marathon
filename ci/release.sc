#!/usr/bin/env amm
// helpers to handle building and releasing packages
def createPackages(): String = utils.stage("Package") {
  val result = %%('sbt, "universal:packageZipTarball", "universal-docs:packageZipTarball", "version")
}

@main
def release(tagName: Option[String]): Unit = {
  println(tagName)
}
