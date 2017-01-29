package com.github.fchiron

import java.util.UUID

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import de.heikoseeberger.akkahttpcirce.CirceSupport._
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto._

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.io.StdIn
import scala.language.postfixOps

case class LoginData(email: String, password: String)

object LoginData {
  implicit val loginDataDecoder: Decoder[LoginData] = deriveDecoder[LoginData]
}

object Main extends App {

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  import system.dispatcher

  sealed abstract class LoginError(message: String, underlying: Option[Throwable]) extends RuntimeException(message, underlying.orNull)

  object LoginError {
    case class UnexpectedStatusCode(response: HttpResponse, status: StatusCode) extends LoginError(s"Received unexpected status code ${status.intValue()}", None)
    case class InvalidRedirectLocation(response: HttpResponse, location: Location) extends LoginError(s"Redirected to unexpected location '${location.uri}'", None)
    case class MissingRedirectLocation(response: HttpResponse) extends LoginError(s"Expected to be redirected but was not", None)
    case class MissingSessionCookie(response: HttpResponse) extends LoginError(s"No _permigo_session cookie in response", None)
  }

  def validateStatus(response: HttpResponse): Future[Unit] = {
    if (response.status.isRedirection()) {
      Future.successful(())
    } else {
      Future.failed(LoginError.UnexpectedStatusCode(response, response.status))
    }
  }

  def validateRedirectLocation(response: HttpResponse): Future[Unit] = {
    response.header[Location] match {
      case Some(Location(uri)) if uri.path == Uri.Path("/mon-compte") => Future.successful(())
      case Some(location) => Future.failed(LoginError.InvalidRedirectLocation(response, location))
      case None => Future.failed(LoginError.MissingRedirectLocation(response))
    }
  }

  def extractPermigoSessionCookie(response: HttpResponse): Future[String] = {
    response.headers collect {
      case `Set-Cookie`(cookie) if cookie.name == "_permigo_session" => cookie.value
    } headOption match {
      case Some(session) => Future.successful(session)
      case None => Future.failed(LoginError.MissingSessionCookie(response))
    }
  }

  case class AuthCacheItem(email: String, session: String)

  object AuthCacheItem {
    implicit val authCacheItemEncoder: Encoder[AuthCacheItem] = deriveEncoder[AuthCacheItem]
  }

  val authCache: collection.mutable.HashMap[UUID, AuthCacheItem] = collection.mutable.HashMap.empty

  val route =
    path("login") {
      post {
        entity(as[LoginData]) { loginData =>
          val fResponse = Http().singleRequest(HttpRequest(
            uri = "https://www.permigo.com/connexion",
            method = HttpMethods.POST,
            entity = FormData(
              "user[email]" -> loginData.email,
              "user[password]" -> loginData.password,
              "user[remember_me]" -> "0"
            ).toEntity
          ))

          val fBodyAndSession = for {
            response <- fResponse
            _ = println(response.headers)
            _ <- validateStatus(response)
            _ <- validateRedirectLocation(response)
            permigoSession <- extractPermigoSessionCookie(response)
            body <- response.entity.dataBytes.runReduce(_ ++ _).map(_.utf8String)
          } yield (body, permigoSession)

          onSuccess(fBodyAndSession) {
            case (body, session) =>
              val token = UUID.randomUUID()

              authCache.update(token, AuthCacheItem(loginData.email, session))
              complete(io.circe.Json.obj("access_token" -> io.circe.Json.fromString(token.toString)))
          }
        }
      }
    } ~ path("test" / JavaUUID) { token =>
      get {
        complete(authCache.get(token))
      }
    }

  val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  StdIn.readLine() // let it run until user presses return
  bindingFuture
    .flatMap(_.unbind()) // trigger unbinding from the port
    .onComplete(_ ⇒ system.terminate()) // and shutdown when done
}
