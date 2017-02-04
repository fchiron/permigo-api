package com.github.fchiron

import java.time.LocalDate
import java.util.UUID

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{ AuthorizationFailedRejection, Directive1 }
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import com.github.fchiron.SessionStore.SessionData
import com.github.fchiron.config.RedisConfig
import com.typesafe.scalalogging.LazyLogging
import de.heikoseeberger.akkahttpcirce.CirceSupport._
import io.circe.Json
import utils.akka.http.scaladsl.unmarshalling._

import scala.concurrent.duration._
import scala.io.StdIn
import scala.language.postfixOps
import scala.util.{ Failure, Success }

object Main extends App with LazyLogging {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  import system.dispatcher

  import org.sedis._
  import redis.clients.jedis._

  val redisPool = {
    pureconfig.loadConfig[RedisConfig]("redis") match {
      case Success(RedisConfig(redisHost, redisPort, redisPassword, redisTimeout)) =>
        redisPassword match {
          case Some(password) => new Pool(new JedisPool(new JedisPoolConfig, redisHost, redisPort, redisTimeout, password))
          case None => new Pool(new JedisPool(new JedisPoolConfig, redisHost, redisPort, redisTimeout))
        }
      case Failure(ex) =>
        logger.error("Could not parse redis config", ex)
        system.terminate()
        throw ex
    }
  }

  val sessionStore = new SessionStore
  val permigoService = new PermigoService
  val subscriptionService = new SubscriptionService(permigoService, redisPool)

  val extractSessionFromAccessToken: Directive1[SessionData] = parameter("access_token".as[UUID]) flatMap { access_token =>
    sessionStore.get(access_token) match {
      case Some(sessionData) => provide(sessionData)
      case None => reject(AuthorizationFailedRejection)
    }
  }

  val route =
    path("login") {
      post {
        entity(as[LoginData]) {
          case LoginData(email, password) =>
          onSuccess(permigoService.login(email, password)) { session =>
            val token = UUID.randomUUID()

            sessionStore.set(token, SessionData(email, session))
            complete(Json.obj("access_token" -> Json.fromString(token.toString)))
          }
        }
      }
    } ~ extractSessionFromAccessToken { session =>
      path("session") {
        get {
          complete(session)
        }
      } ~ path("cities") {
        get {
          onSuccess(permigoService.getCities(session.session)) { cities =>
            complete(cities)
          }
        }
      } ~ path("instructors") {
        get {
          parameter("city_id".as[Int]) { city_id =>
            onSuccess(permigoService.getInstructors(session.session, city_id)) { instructors =>
              complete(instructors)
            }
          }
        }
      } ~ pathPrefix("available_slots") {
        pathEndOrSingleSlash {
          get {
            parameters("instructor_id".as[Int], "start".as[LocalDate], "end".as[LocalDate], "ignore_max_workhours".as[Boolean] ? false) { (instructor_id, start, end, ignoreMaxWorkHours) =>
              val fSlots = permigoService.getAvailableSlots(session.session, instructor_id, start, end, ignoreMaxWorkHours)

              onSuccess(fSlots) { slots =>
                complete(slots)
              }
            }
          }
        } ~ path("subscriptions") {
          get {
            subscriptionService.getSubscription(session) match {
              case None => complete(StatusCodes.NotFound)
              case Some(slots) => complete(slots)
            }
          } ~ post {
            entity(as[AvailableSlotsSubscription]) { subscription =>
              subscriptionService.addSubscription(session, subscription)
              complete(StatusCodes.OK)
            }
          }
        }
      }
    }

  // Setup subscription worker
  //system.scheduler.schedule(initialDelay = 10.seconds, interval = 1.minute) {
  system.scheduler.schedule(initialDelay = 10.seconds, interval = 10.seconds) {
    subscriptionService.checkAvailableSlotsMatchingSubscriptions()
  }

  val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  StdIn.readLine() // let it run until user presses return
  bindingFuture
    .flatMap(_.unbind()) // trigger unbinding from the port
    .onComplete(_ ⇒ system.terminate()) // and shutdown when done
}
