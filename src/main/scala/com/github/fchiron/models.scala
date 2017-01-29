package com.github.fchiron

import java.time.ZonedDateTime

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto._

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
