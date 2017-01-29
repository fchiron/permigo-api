package com.github.fchiron

import java.time.LocalDate

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.{ Cookie, Location, `Set-Cookie` }
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import net.ruippeixotog.scalascraper.browser.JsoupBrowser
import net.ruippeixotog.scalascraper.dsl.DSL._
import net.ruippeixotog.scalascraper.dsl.DSL.Extract._

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.language.postfixOps

class PermigoService(sessionStore: SessionStore)(implicit system: ActorSystem, materializer: ActorMaterializer) {
  import system.dispatcher

  val baseURL = "https://www.permigo.com"
  val jsoupBrowser = JsoupBrowser()

  object Endpoints {
    val connexion = s"$baseURL/connexion"
    val planning = s"$baseURL/mon-compte/mon-planning"
    val instructors = s"$baseURL/mon-compte/mon-planning/instructor_details"
  }

  private def permigoSessionCookie(session: String): Cookie = Cookie("_permigo_session", session)

  /**
   * Simulate a form submission to login on the Permigo website and get
   * a session identifier required for all other calls.
   * @param email Email
   * @param password Password
   * @return Session identifier to reuse for other calls
   */
  def login(email: String, password: String): Future[String] = {
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

    val fResponse = Http().singleRequest(HttpRequest(
      uri = Endpoints.connexion,
      method = HttpMethods.POST,
      entity = FormData(
        "user[email]" -> email,
        "user[password]" -> password,
        "user[remember_me]" -> "0"
      ).toEntity
    ))

    for {
      response <- fResponse
      _ <- validateStatus(response)
      _ <- validateRedirectLocation(response)
      permigoSession <- extractPermigoSessionCookie(response)
    } yield permigoSession
  }

  /**
   * Get the available cities for the session's account.
   * @param session Permigo session identifier
   * @return
   */
  def getCities(session: String): Future[List[City]] = {
    Http().singleRequest(HttpRequest(
      uri = Endpoints.planning,
      headers = Seq(permigoSessionCookie(session))
    )) flatMap { response =>
      if (response.status.intValue() == 200) {
        response.entity.dataBytes.runReduce(_ ++ _).map(_.utf8String)
      } else {
        // TODO: Cleaner error handling
        Future.failed(new RuntimeException("Status code != 200"))
      }
    } map { body =>
      val doc = jsoupBrowser.parseString(body)

      doc >> element("select#filter_city") >> elementList("option") map { element =>
        val id = attr("value")(element)
        val name = text(element)

        (id, name)
      } collect {
        case (id, name) if id.nonEmpty => City(id.toInt, name)
      }
    }
  }

  /**
   * Get instructors for the given city, for the session's account.
   * @param session Permigo session identifier
   * @param city_id City id
   * @return
   */
  def getInstructors(session: String, city_id: Int): Future[List[Instructor]] = {
    // e.g. "https://www.permigo.com/mon-compte/mon-planning/instructor_details?rdv_loc_title=&sector_city_id=17200&date="
    Http().singleRequest(HttpRequest(
      uri = Uri(Endpoints.instructors).withQuery(Query("sector_city_id" -> city_id.toString)),
      headers = Seq(permigoSessionCookie(session))
    )) flatMap { response =>
      if (response.status.intValue() == 200) {
        response.entity.dataBytes.runReduce(_ ++ _).map(_.utf8String)
      } else {
        // TODO: Cleaner error handling
        Future.failed(new RuntimeException("Status code != 200"))
      }
    } map { body =>
      val doc = jsoupBrowser.parseString(body)

      doc >> elementList("label.instructor-radio") map { element =>
        val instructor_id = element >> attr("value")("input[name=instructor_id]")
        val name = element >> text("div.caption")

        Instructor(instructor_id.toInt, name)
      }
    }
  }
}
