package com.github.fchiron

import akka.http.scaladsl.model.{ HttpResponse, StatusCode }
import akka.http.scaladsl.model.headers.Location

sealed abstract class LoginError(message: String, underlying: Option[Throwable]) extends RuntimeException(message, underlying.orNull)

object LoginError {
  case class UnexpectedStatusCode(response: HttpResponse, status: StatusCode) extends LoginError(s"Received unexpected status code ${status.intValue()}", None)
  case class InvalidRedirectLocation(response: HttpResponse, location: Location) extends LoginError(s"Redirected to unexpected location '${location.uri}'", None)
  case class MissingRedirectLocation(response: HttpResponse) extends LoginError(s"Expected to be redirected but was not", None)
  case class MissingSessionCookie(response: HttpResponse) extends LoginError(s"No _permigo_session cookie in response", None)
}
