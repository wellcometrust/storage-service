package uk.ac.wellcome.platform.archive.common.services

import java.time.Instant

import com.amazonaws.services.s3.AmazonS3
import uk.ac.wellcome.platform.archive.common.parsers.{BagInfoParser, FileManifestParser}
import uk.ac.wellcome.platform.archive.common.models.bagit._
import uk.ac.wellcome.platform.archive.common.models.{BagRequest, ChecksumAlgorithm, StorageManifest}
import uk.ac.wellcome.platform.archive.common.progress.models.{InfrequentAccessStorageProvider, StorageLocation}

import scala.concurrent.{ExecutionContext, Future}

class StorageManifestService(s3Client: AmazonS3)(
  implicit ec: ExecutionContext
) {

  val checksumAlgorithm = ChecksumAlgorithm("sha256")

  def createManifest(bagLocation: BagLocation): Future[StorageManifest] = for {
    bagInfo <- createBagInfo(bagLocation)
    fileManifest <- createFileManifest(bagLocation)
    tagManifest <- createTagManifest(bagLocation)
  } yield StorageManifest(
      space = bagLocation.storageSpace,
      info = bagInfo,
      manifest = fileManifest,
      tagManifest = tagManifest,
      accessLocation = StorageLocation(
        provider = InfrequentAccessStorageProvider,
        location = bagLocation.objectLocation
      ),
      archiveLocations = List.empty,
      createdDate = Instant.now()
    )

  def createBagInfo(bagLocation: BagLocation): Future[BagInfo] = for {
    bagInfoInputStream <- BagIt
      .bagInfoPath
      .toObjectLocation(bagLocation)

    bagInfo <- BagInfoParser.create(
      bagInfoInputStream
    )
  } yield bagInfo

  def createFileManifest(bagLocation: BagLocation) ={
    createManifest(
      s"manifest-$checksumAlgorithm.txt",
      bagLocation
    )
  }

  def createTagManifest(bagLocation: BagLocation) ={
    createManifest(
      s"tagmanifest-$checksumAlgorithm.txt",
      bagLocation
    )
  }

  private def createManifest(name: String, bagLocation: BagLocation) = for {
    fileManifestInputStream <- BagItemPath(name)
      .toObjectLocation(bagLocation)

    fileManifest <- FileManifestParser.create(
      fileManifestInputStream, checksumAlgorithm
    )
  } yield fileManifest

}
