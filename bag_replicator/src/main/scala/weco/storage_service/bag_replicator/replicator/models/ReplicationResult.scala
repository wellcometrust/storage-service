package weco.storage_service.bag_replicator.replicator.models

import weco.storage.{Location, Prefix}

sealed trait ReplicationResult[DstPrefix <: Prefix[_ <: Location]] {
  val summary: ReplicationSummary[DstPrefix]
}

case class ReplicationSucceeded[DstPrefix <: Prefix[_ <: Location]](
  summary: ReplicationSummary[DstPrefix]
) extends ReplicationResult[DstPrefix]

case class ReplicationFailed[DstPrefix <: Prefix[_ <: Location]](
  summary: ReplicationSummary[DstPrefix],
  e: Throwable
) extends ReplicationResult[DstPrefix]
