package com.github.fchiron.utils.akka.http.scaladsl

import java.time.LocalDate
import java.util.UUID

import akka.http.scaladsl.unmarshalling.Unmarshaller

import scala.concurrent.Future

package object unmarshalling {
  implicit val uuidFromStringUnmarshaller: Unmarshaller[String, UUID] =
    Unmarshaller(ec => s => Future(UUID.fromString(s))(ec))

  implicit val dateFromStringUnmarshaller: Unmarshaller[String, LocalDate] =
    Unmarshaller(ec => s => Future(LocalDate.parse(s))(ec))
}
