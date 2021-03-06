package weco.storage_service.bag_replicator.config

import com.typesafe.config.Config
import weco.storage_service.bag_replicator.models._
import weco.storage_service.ingests.models.StorageProvider
import weco.typesafe.config.builders.EnrichConfig._

case class ReplicatorDestinationConfig(
  namespace: String,
  provider: StorageProvider,
  replicaType: ReplicaType
)

case object ReplicatorDestinationConfig {
  def buildDestinationConfig(config: Config): ReplicatorDestinationConfig = {
    val replicaTypeString = config.requireString("bag-replicator.replicaType")

    val replicaType: ReplicaType =
      replicaTypeString match {
        case "primary"   => PrimaryReplica
        case "secondary" => SecondaryReplica
        case _ =>
          throw new IllegalArgumentException(
            s"Unrecognised replica type: $replicaTypeString, expected primary/secondary"
          )
      }

    ReplicatorDestinationConfig(
      namespace =
        config.requireString("bag-replicator.storage.destination.namespace"),
      provider = StorageProvider(
        config.requireString("bag-replicator.provider")
      ),
      replicaType = replicaType
    )
  }
}
