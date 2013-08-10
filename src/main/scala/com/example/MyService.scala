package com.example

import scala.concurrent._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import akka.actor._
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import spray.can.Http
import spray.http._
import spray.http.HttpMethods._
import spray.http.MediaTypes._
import spray.util._
import akka.io.Tcp
import java.net.InetSocketAddress

class HttpLogger extends Actor with SprayActorLogging {
  def receive = {
    case (connected: InetSocketAddress, r: HttpRequest) => log.info(s"Http Request from $connected $r")
  }
}

class MyServiceActor extends MyService {
}

trait MyService extends Actor with SprayActorLogging {
  val httpLogger: ActorRef = context actorOf Props[HttpLogger]

  def receive = {
    // when a new connection comes in we register ourselves as the connection handler
    case c: Http.Connected =>
      val handler = context actorOf Props(classOf[ConnectionHandler], httpLogger, c.remoteAddress)
      sender ! Http.Register(handler)
  }
}

class ConnectionHandler(httpLogger: ActorRef, connected: InetSocketAddress) extends Actor with SprayActorLogging {
  log.debug(s"Connection opened: $connected")

  def receive = {
    case request: HttpRequest =>
      httpLogger ! (connected, request)
      request.method match {
        case GET => handleRequest(sender, request)
        case POST => handleRequest(sender, request)
        case HEAD => handleRequest(sender, request)
        case _ => sender ! HttpResponse(status = 405, entity = "Method not supported")
      }

    case _: Http.ConnectionClosed =>
      log.debug("Connection closed: $connected")
      context stop self

    case Timedout(_) =>
      sender ! HttpResponse(status = 500, entity = "The request has timed out...")
  }

  def handleRequest(requester: ActorRef, request: HttpRequest) {
    (context actorOf Props[RequestHandler]) ! (requester, request)
  }
}

class RequestHandler extends Actor with SprayActorLogging {
  import context._
  val httpBridge: ActorRef = IO(Http)
  val authority = Uri.Authority(Uri.Host("localhost"), 80)

  def receive = created
  
  def created: Receive = {
    case (requester: ActorRef, request: HttpRequest) =>
      val headers = request.headers.filter(_.lowercaseName != "host").filter(_.lowercaseName != "user-agent")
      val proxiedRequest = request.copy(uri = request.uri.copy("http", authority = authority), headers = headers)
      httpBridge ! Http.HostConnectorSetup("localhost", port = 80)
      become(awaitingConnector(requester, proxiedRequest))
  }

  def awaitingConnector(requester: ActorRef, request: HttpRequest): Receive = {
    case Http.HostConnectorInfo(hostConnector, _) =>
      hostConnector ! request
      become(awaitingResponse(requester))
  }

  def awaitingResponse(requester: ActorRef): Receive = {
    case httpResponsePart: HttpResponsePart =>
      val httpResponse = httpResponsePart.messagePart.asInstanceOf[HttpResponse]
      log.debug(s"Http Response $httpResponse")
      val headers = httpResponse.headers.filter(_.lowercaseName != "date")
        .filter(_.lowercaseName != "content-length")
        .filter(_.lowercaseName != "content-type")
        .filter(_.lowercaseName != "server")
      val copiedResponse = httpResponse.copy(headers = headers)
      requester ! copiedResponse
      context stop self

    case Status.Failure(exception) =>
      requester ! HttpResponse(status = 502, entity = "Bad Gateway")
      log.error(exception, "Exception in backend")
      context stop self
  }
}