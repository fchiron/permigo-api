package com.github.fchiron

import java.time.{ LocalDate, ZoneOffset }

import com.github.fchiron.SessionStore.SessionData
import org.sedis.Pool

import scala.collection.mutable
import scala.concurrent.Future
import scala.language.postfixOps
import scalaz.{ Applicative, Apply, ListT }
import scala.concurrent.ExecutionContext.Implicits.global

class SubscriptionService(permigoService: PermigoService, redisPool: Pool) {
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
              redisPool withClient { client =>
                val key = notifiedSlotRedisKey(session.email, instructor.id, timeSlot)

                // `setnx` returns 0 if the key already existed, hence we already sent a notification for this slot
                val alreadyNotified = client.setnx(key, "1") == 0
                client.expireAt(key, timeSlot.start.toEpochSecond)

                if (!alreadyNotified) {
                  notifySubscription(session, subscription, instructor, timeSlot)
                } else {
                  println(s"""  => already sent notification for this slot""")
                }
              }
          }
        }
    }
  }

  def notifiedSlotRedisKey(email: String, instructor_id: Int, timeSlot: TimeSlot): String = {
    s"$email:notified_slot:$instructor_id:${timeSlot.start.toEpochSecond}"
  }

  def notifySubscription(session: SessionData, subscription: AvailableSlotsSubscription, instructor: Instructor, timeSlot: TimeSlot): Unit = {
    val message = s"""Notification! A new time slot is available:\n  Date: ${timeSlot.start}\n  Instructor: ${instructor.name}"""

    println(message)
  }
}
