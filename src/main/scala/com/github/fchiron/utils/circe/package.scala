package com.github.fchiron.utils

import java.time.ZonedDateTime

import io.circe.{ Decoder, Encoder, Json }

import scala.util.Try

package object circe {
  implicit val zonedDateTimeDecoder: Decoder[ZonedDateTime] = Decoder[String] emap { s =>
    Try(ZonedDateTime.parse(s)).toOption.toRight(s"Could not parse $s as ZonedDateTime")
  }

  implicit val zonedDateTimeEncoder: Encoder[ZonedDateTime] = Encoder.instance[ZonedDateTime](zonedDateTime => Json.fromString(zonedDateTime.toString))
}
