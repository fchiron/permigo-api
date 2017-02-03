package com.github.fchiron

import java.time.ZonedDateTime

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto._
import utils.circe._

case class LoginData(email: String, password: String)

object LoginData {
  implicit val loginDataDecoder: Decoder[LoginData] = deriveDecoder[LoginData]
}

case class City(id: Int, name: String)

object City {
  implicit val cityEncoder: Encoder[City] = deriveEncoder[City]
}

case class Instructor(id: Int, name: String)

object Instructor {
  implicit val instructorEncoder: Encoder[Instructor] = deriveEncoder[Instructor]
}

case class TimeSlot(start: ZonedDateTime, end: ZonedDateTime)

object TimeSlot {
  implicit val timeSlotEncoder: Encoder[TimeSlot] = deriveEncoder[TimeSlot]
}

// TODO: Enum/ADT for notification channels
case class AvailableSlotsSubscription(notificationChannels: List[String], availabilities: Option[List[AvailabilityTimeRange]])

object AvailableSlotsSubscription {
  implicit val availableSlotsSubscriptionDecoder: Decoder[AvailableSlotsSubscription] = deriveDecoder[AvailableSlotsSubscription]

  implicit val availableSlotsSubscriptionEncoder: Encoder[AvailableSlotsSubscription] = deriveEncoder[AvailableSlotsSubscription]
}

case class AvailabilityTimeRange(weekDay: Int, start: Int, end: Int)

object AvailabilityTimeRange {
  implicit val availabilityTimeRangeDecoder: Decoder[AvailabilityTimeRange] = deriveDecoder[AvailabilityTimeRange]

  implicit val availabilityTimeRangeEncoder: Encoder[AvailabilityTimeRange] = deriveEncoder[AvailabilityTimeRange]
}
