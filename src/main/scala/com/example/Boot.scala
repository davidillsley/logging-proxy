package com.example

import akka.actor.{ActorSystem, Props}
import akka.io.IO
import spray.can.Http

object Boot extends App {
  println("Boot")

  // we need an ActorSystem to host our application in
  implicit val system = ActorSystem("on-spray-can")

  // create and start our service actor
  val service = system.actorOf(Props[MyServiceActor], "demo-service")

  // start a new HTTP server on port 8080 with our service actor as the handler
  val port = Option(System.getenv("PORT")).getOrElse("8080").toInt
  val bindTo = Option(System.getenv("VCAP_APP_HOST")).getOrElse("localhost")
  println("Port" + port)
  println("Interface" + bindTo)
  IO(Http) ! Http.Bind(service, interface = bindTo, port = port)
  
  println("Started")
}