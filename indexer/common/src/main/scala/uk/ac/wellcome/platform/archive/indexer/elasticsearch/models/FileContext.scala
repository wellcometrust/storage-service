package uk.ac.wellcome.platform.archive.indexer.elasticsearch.models

import java.time.Instant

import weco.storage_service.bagit.models.ExternalIdentifier
import weco.storage_service.storage.models.{
  PrimaryS3StorageLocation,
  StorageManifest,
  StorageManifestFile,
  StorageSpace
}
import weco.storage_service.verify.HashingAlgorithm
import weco.storage.s3.S3ObjectLocation

case class FileContext(
  space: StorageSpace,
  externalIdentifier: ExternalIdentifier,
  hashingAlgorithm: HashingAlgorithm,
  bagLocation: PrimaryS3StorageLocation,
  file: StorageManifestFile,
  createdDate: Instant
) {
  def location: S3ObjectLocation =
    bagLocation.prefix.asLocation(file.path)
}

case object FileContext {
  def apply(
    manifest: StorageManifest,
    file: StorageManifestFile
  ): FileContext = {
    assert(manifest.manifest.files.contains(file))
    FileContext(
      space = manifest.space,
      externalIdentifier = manifest.info.externalIdentifier,
      hashingAlgorithm = manifest.manifest.checksumAlgorithm,
      bagLocation = manifest.location.asInstanceOf[PrimaryS3StorageLocation],
      file = file,
      createdDate = manifest.createdDate
    )
  }
}
