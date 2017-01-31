package com.github.fchiron

import java.time._
import java.time.temporal.{ ChronoUnit, TemporalAccessor, WeekFields }
import java.util.Locale

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.{ Cookie, Location, RawHeader, `Set-Cookie` }
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import net.ruippeixotog.scalascraper.browser.JsoupBrowser
import net.ruippeixotog.scalascraper.dsl.DSL._
import net.ruippeixotog.scalascraper.dsl.DSL.Extract._
import utils.circe._

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.language.postfixOps

class PermigoService(implicit system: ActorSystem, materializer: ActorMaterializer) {
  import PermigoService._
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

  /**
   * Get available slots for the given instructor between `start` (inclusive) and `end` (exclusive).
   * If `ignoreMaxWorkHours` is set, do not return slots for weeks where 35 hours are already booked for that instructor.
   * @param session Permigo session identifier
   * @param instructor_id Instructor id (see [[getInstructors]])
   * @param start Start date
   * @param end End date (exclusive)
   * @param ignoreMaxWorkHours If true, return slots even if the maximum work hours of the instructor are reached
   * @return
   */
  def getAvailableSlots(session: String, instructor_id: Int, start: LocalDate, end: LocalDate, ignoreMaxWorkHours: Boolean): Future[List[TimeSlot]] = {
    // Permigo instructors can not have more than 35 hours booked per week
    val MAX_HOURS_PER_WEEK = 35
    // Days on Permigo's calendar start at 07h00 and end at 21h00 (so the last slot is at 20h00)
    val FIRST_SLOT_HOUR = 7
    val LAST_SLOT_HOUR = 20
    val weekOfYearField = WeekFields.of(Locale.FRENCH).weekOfWeekBasedYear()
    val realStart = start.`with`(DayOfWeek.MONDAY)
    val realEnd = end.`with`(DayOfWeek.SUNDAY)

    Http().singleRequest(HttpRequest(
      uri = Uri(s"${Endpoints.planning}.json").withQuery(Query("instructor_id" -> instructor_id.toString, "start" -> realStart.toString, "end" -> realEnd.toString)),
      // The X-Requested-With header is required, otherwise Permigo redirects us to the planning page
      headers = Seq(permigoSessionCookie(session), RawHeader("X-Requested-With", "XMLHttpRequest"))
    )) flatMap { response =>
      if (response.status.intValue() == 200) {
        response.entity.dataBytes.runReduce(_ ++ _).map(_.utf8String)
      } else {
        // TODO: Cleaner error handling
        Future.failed(new RuntimeException("Status code != 200"))
      }
    } flatMap { body =>
      io.circe.parser.decode[List[PlanningEvent]](body) map { planningEvents =>
        val workHoursByWeek = planningEvents.groupBy(_.start.get(weekOfYearField)) mapValues { events =>
          events filter { e =>
            // "reserved" represents slots booked by someone else,
            // "schedule" represents slots booked by you,
            // "schedule" + "other" represents slots booked by you, but with another instructor
            e.className.contains("reserved") || (e.className.contains("schedule") && !e.className.contains("other"))
          } map { e =>
            e.start.until(e.end, ChronoUnit.HOURS)
          } sum
        }

        planningEvents groupBy (_.start.toLocalDate) filterKeys { date =>
          // We requested a wider time range to get full weeks, to calculate the work hours per week.
          // Now that we have the work hours per week, drop all the events outside the requested time range.
          !date.isBefore(start) && !date.isAfter(end)
        } filterKeys { date =>
          ignoreMaxWorkHours || workHoursByWeek.getOrElse(date.get(weekOfYearField), sys.error("Implementation error, could not find work hours for week")) < MAX_HOURS_PER_WEEK
        } flatMap {
          case (date, events) =>
            val slots = (FIRST_SLOT_HOUR to LAST_SLOT_HOUR).map(begin => (begin, begin + 1))

            events.foldLeft(slots) {
              case (remainingSlots, event) => remainingSlots filterNot {
                case (slotStart, slotEnd) => event.start.getHour <= slotStart && event.end.getHour >= slotEnd
              }
            } map {
              case (slotStart, slotEnd) =>
                val dateZonedDateTime = date.atStartOfDay(ZoneOffset.UTC).withFixedOffsetZone

                TimeSlot(
                  dateZonedDateTime.withHour(slotStart),
                  dateZonedDateTime.withHour(slotEnd)
                )
            }
        } toList
      } match {
        case Left(error) => Future.failed(error)
        case Right(slots) => Future.successful(slots.sortBy(_.start)(Ordering.fromLessThan(_ isBefore _)))
      }
    }
  }
}

object PermigoService {
  case class PlanningEvent(id: Int, start: ZonedDateTime, end: ZonedDateTime, allDay: Boolean, title: String, className: String, details: String)

  object PlanningEvent {
    implicit val planningItemDecoder: Decoder[PlanningEvent] = deriveDecoder[PlanningEvent]
  }
}