package com.github.fchiron

import java.time.{ LocalDate, ZoneOffset }

import com.github.fchiron.SessionStore.SessionData

import scala.collection.mutable
import scala.concurrent.Future
import scala.language.postfixOps
import scalaz.{ Applicative, Apply, ListT }
import scala.concurrent.ExecutionContext.Implicits.global

class SubscriptionService(permigoService: PermigoService) {
  val subscribedSessions = new mutable.HashMap[SessionData, AvailableSlotsSubscription]

  def getSubscription(sessionData: SessionData): Option[AvailableSlotsSubscription] = {
    subscribedSessions.get(sessionData)
  }

  def addSubscription(sessionData: SessionData, subscription: AvailableSlotsSubscription): Unit = {
    subscribedSessions.update(sessionData, subscription)
  }

  /**
   * Check for available slots in the next 90 days, and notify any matching subscription.
   */
  def checkAvailableSlotsMatchingSubscriptions(): Unit = {
    import scalaz.std.scalaFuture._
    import scalaz.std.list._
    import scalaz.syntax.semigroup._

    def timeSlotMatchesSubscription(timeSlot: TimeSlot, subscription: AvailableSlotsSubscription): Boolean = {
      subscription match {
        // No specific availability time range -> considered as always available
        case AvailableSlotsSubscription(notificationChannels, None) => true
        // Availability time ranges specified -> check if they match
        case AvailableSlotsSubscription(notificationChannels, Some(availabilityTimeRanges)) =>
          availabilityTimeRanges exists {
            case AvailabilityTimeRange(weekDay, start, end) =>
              weekDay == timeSlot.start.getDayOfWeek.getValue && start <= timeSlot.start.getHour && timeSlot.end.getHour <= end
          }
      }
    }

    subscribedSessions foreach {
      case (session, subscription) =>
        val today = LocalDate.now(ZoneOffset.UTC)

        (for {
          city <- ListT(permigoService.getCities(session.session))
          instructor <- ListT(permigoService.getInstructors(session.session, city.id))
          slot <- ListT {
            permigoService.getAvailableSlots(session.session, instructor.id, today, today.plusMonths(1), ignoreMaxWorkHours = false) |+|
              permigoService.getAvailableSlots(session.session, instructor.id, today.plusMonths(1), today.plusMonths(2), ignoreMaxWorkHours = false) |+|
              permigoService.getAvailableSlots(session.session, instructor.id, today.plusMonths(2), today.plusMonths(3), ignoreMaxWorkHours = false)
          }
          if timeSlotMatchesSubscription(slot, subscription)
        } yield {
          (instructor, slot)
        }).run foreach { matches =>
          matches foreach {
            case (instructor, timeSlot) =>
              println(s"""match: instructor: $instructor, timeSlot: $timeSlot""")
              notifySubscription(session, subscription, instructor, timeSlot)
          }
        }
    }
  }

  def notifySubscription(session: SessionData, subscription: AvailableSlotsSubscription, instructor: Instructor, timeSlot: TimeSlot): Unit = {
    val message = s"""A new time slot is available:\nDate: ${timeSlot.start}\nInstructor: ${instructor.name}"""

    println(message)
  }
}
