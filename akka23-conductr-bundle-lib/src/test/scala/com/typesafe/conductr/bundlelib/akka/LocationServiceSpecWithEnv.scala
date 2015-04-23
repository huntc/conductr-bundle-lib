/*
 * Copyright © 2014-2015 Typesafe, Inc. All rights reserved.
 * No information contained herein may be reproduced or transmitted in any form
 * or by any means without the express written permission of Typesafe, Inc.
 */

package com.typesafe.conductr.bundlelib.akka

import java.net.{ InetSocketAddress, URL }

import akka.actor._
import akka.http.Http
import akka.http.model.headers.{ CacheDirectives, Location, `Cache-Control` }
import akka.http.model.{ HttpEntity, HttpResponse, StatusCodes }
import akka.http.server.Directives._
import akka.stream.ActorFlowMaterializer
import akka.testkit.TestProbe
import com.typesafe.conductr.bundlelib.scala.LocationCache
import com.typesafe.conductr.{ AkkaUnitTest, _ }

import scala.concurrent.Await
import scala.util.{ Failure, Success }

class LocationServiceSpecWithEnv extends AkkaUnitTest("LocationServiceSpecWithEnv", "akka.loglevel = INFO") {

  "The LocationService functionality in the library" should {

    "be able to look up a named service" in {
      implicit val cc = ConnectionContext()

      val serviceUri = "http://service_interface:4711/known"
      withServerWithKnownService(serviceUri) {

        val service = LocationService.lookup("/known")
        Await.result(service, timeout.duration) shouldBe Some(serviceUri)
      }
    }

    "be able to look up a named service using a cache" in {
      implicit val cc = ConnectionContext()

      val serviceUri = "http://service_interface:4711/known"
      withServerWithKnownService(serviceUri) {

        val cache = LocationCache()
        val service = LocationService.lookup("/known", cache)
        Await.result(service, timeout.duration) shouldBe Some(serviceUri)
      }
    }

    "be able to look up a named service within an actor" in {
      import akka.pattern.pipe

      class MyService(observer: ActorRef) extends Actor with ImplicitConnectionContext {

        import context.dispatcher

        override def preStart(): Unit =
          LocationService.lookup("/known").pipeTo(self)

        override def receive: Receive = {
          case Some(someService: String) =>
            // We now have the service

            observer ! someService

          case None =>
            self ! (if (Env.isRunByConductR) PoisonPill else Some("http://127.0.0.1:9000"))
        }
      }

      val serviceUri = "http://service_interface:4711/known"
      withServerWithKnownService(serviceUri) {

        val testProbe = TestProbe()
        system.actorOf(Props(new MyService(testProbe.ref)))
        testProbe.expectMsg(serviceUri)
      }
    }
  }

  def withServerWithKnownService(serviceUrl: String, maxAge: Option[Int] = None)(thunk: => Unit): Unit = {
    import system.dispatcher
    implicit val materializer = ActorFlowMaterializer.create(system)

    val probe = new TestProbe(system)

    val handler =
      path("services" / Rest) { serviceName =>
        get {
          complete {
            serviceName match {
              case "known" =>
                val headers = Location(serviceUrl) :: (maxAge match {
                  case Some(maxAgeSecs) =>
                    `Cache-Control`(
                      CacheDirectives.`private`(Location.name),
                      CacheDirectives.`max-age`(maxAgeSecs)) :: Nil
                  case None =>
                    Nil
                })
                HttpResponse(StatusCodes.TemporaryRedirect, headers, HttpEntity(s"Located at $serviceUrl"))
              case _ =>
                HttpResponse(StatusCodes.NotFound)
            }
          }
        }
      }

    val url = new URL(Env.serviceLocator.get)
    val server = Http(system).bindAndStartHandlingWith(handler, url.getHost, url.getPort, settings = None)

    try {
      server.onComplete {
        case Success(binding) => probe.ref ! binding.localAddress
        case Failure(e)       => probe.ref ! e
      }

      val address = probe.expectMsgType[InetSocketAddress]
      address.getHostString should be(url.getHost)
      address.getPort should be(url.getPort)

      thunk
    } finally {
      server.foreach(_.unbind())
    }
  }
}