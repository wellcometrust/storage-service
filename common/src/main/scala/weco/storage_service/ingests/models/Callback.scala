package weco.storage_service.ingests.models

import java.net.URI

import weco.storage_service.ingests.models.Callback.Pending

case class Callback(uri: URI, status: Callback.CallbackStatus = Pending)

case object Callback {
  sealed trait CallbackStatus

  def apply(callbackUri: URI): Callback = {
    Callback(uri = callbackUri)
  }

  def apply(callbackUri: Option[URI]): Option[Callback] = {
    callbackUri.map(Callback(_))
  }

  case object Pending extends CallbackStatus {
    override def toString: String = "pending"
  }

  case object Succeeded extends CallbackStatus {
    override def toString: String = "succeeded"
  }

  case object Failed extends CallbackStatus {
    override def toString: String = "failed"
  }
}
