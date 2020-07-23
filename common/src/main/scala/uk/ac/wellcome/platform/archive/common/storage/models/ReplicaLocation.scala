package uk.ac.wellcome.platform.archive.common.storage.models

import uk.ac.wellcome.platform.archive.common.ingests.models.{
  AmazonS3StorageProvider,
  AzureBlobStorageProvider
}
import uk.ac.wellcome.storage.{
  AzureBlobItemLocationPrefix,
  Location,
  Prefix,
  S3ObjectLocationPrefix
}

sealed trait ReplicaLocation {
  val prefix: Prefix[_ <: Location]

  // TODO: Bridging code while we split ObjectLocation.  Remove this later.
  // See https://github.com/wellcomecollection/platform/issues/4596
  def toStorageLocation: StorageLocation
}

sealed trait S3ReplicaLocation extends ReplicaLocation {
  val prefix: S3ObjectLocationPrefix
}

sealed trait PrimaryReplicaLocation extends ReplicaLocation {
  override def toStorageLocation: PrimaryStorageLocation
}

sealed trait SecondaryReplicaLocation extends ReplicaLocation {
  override def toStorageLocation: SecondaryStorageLocation
}

case class PrimaryS3ReplicaLocation(
  prefix: S3ObjectLocationPrefix
) extends S3ReplicaLocation
    with PrimaryReplicaLocation {
  override def toStorageLocation: PrimaryStorageLocation =
    PrimaryStorageLocation(
      provider = AmazonS3StorageProvider,
      prefix = prefix.toObjectLocationPrefix
    )
}

case class SecondaryS3ReplicaLocation(
  prefix: S3ObjectLocationPrefix
) extends S3ReplicaLocation
    with SecondaryReplicaLocation {
  override def toStorageLocation: SecondaryStorageLocation =
    SecondaryStorageLocation(
      provider = AmazonS3StorageProvider,
      prefix = prefix.toObjectLocationPrefix
    )
}

case class SecondaryAzureReplicaLocation(
  prefix: AzureBlobItemLocationPrefix
) extends SecondaryReplicaLocation {
  override def toStorageLocation: SecondaryStorageLocation =
    SecondaryStorageLocation(
      provider = AzureBlobStorageProvider,
      prefix = prefix.toObjectLocationPrefix
    )
}
