package com.github.fchiron

import java.util.UUID

import io.circe.Encoder
import io.circe.generic.semiauto._

import scala.collection.mutable

class SessionStore {
  import SessionStore._

  private val sessionMap: mutable.HashMap[UUID, SessionData] = collection.mutable.HashMap.empty

  def get(access_token: UUID): Option[SessionData] = sessionMap.get(access_token)

  def set(access_token: UUID, sessionData: SessionData): Unit = sessionMap.update(access_token, sessionData)
}

object SessionStore {
  case class SessionData(email: String, session: String)

  object SessionData {
    implicit val authCacheItemEncoder: Encoder[SessionData] = deriveEncoder[SessionData]
  }
}
