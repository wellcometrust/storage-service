package uk.ac.wellcome.platform.archive.bagverifier.services

import java.time.{Duration, Instant}

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import com.amazonaws.services.s3.AmazonS3
import grizzled.slf4j.Logging
import uk.ac.wellcome.platform.archive.bagverifier.models.{
  BagVerification,
  FailedVerification
}
import uk.ac.wellcome.platform.archive.common.models.FileManifest
import uk.ac.wellcome.platform.archive.common.models.bagit.{
  BagDigestFile,
  BagLocation
}
import uk.ac.wellcome.platform.archive.common.services.StorageManifestService
import uk.ac.wellcome.platform.archive.common.storage.ChecksumVerifier

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class VerifyDigestFilesService(
  storageManifestService: StorageManifestService,
  s3Client: AmazonS3,
  algorithm: String)(implicit ec: ExecutionContext, materializer: Materializer)
    extends Logging {
  def verifyBagLocation(bagLocation: BagLocation): Future[BagVerification] =
    for {
      fileManifest <- getManifest("file manifest") {
        storageManifestService.createFileManifest(bagLocation)
      }
      tagManifest <- getManifest("tag manifest") {
        storageManifestService.createTagManifest(bagLocation)
      }
      digestFiles = fileManifest.files ++ tagManifest.files
      result <- verifyFiles(bagLocation, digestFiles)
    } yield result

  private def getManifest(name: String)(
    result: Future[FileManifest]): Future[FileManifest] =
    result.recover {
      case err: Throwable =>
        throw new RuntimeException(s"Error getting $name: ${err.getMessage}")
    }

  private def verifyFiles(
    bagLocation: BagLocation,
    digestFiles: Seq[BagDigestFile]
  )(implicit materializer: Materializer): Future[BagVerification] = {
    val verificationStart = Instant.now

    Source[BagDigestFile](
      digestFiles.toList
    ).mapAsync(10) { digestFile: BagDigestFile =>
        Future(verifyIndividualFile(bagLocation, digestFile = digestFile))
      }
      .runWith(Sink.fold(BagVerification(
        duration = Duration.between(verificationStart, Instant.now))) {
        (memo, item) =>
          item match {
            case Left(failedVerification) =>
              memo.copy(
                failedVerifications =
                  memo.failedVerifications :+ failedVerification)
            case Right(digestFile) =>
              memo.copy(
                successfulVerifications =
                  memo.successfulVerifications :+ digestFile)
          }
      })
  }

  private def verifyIndividualFile(
    bagLocation: BagLocation,
    digestFile: BagDigestFile): Either[FailedVerification, BagDigestFile] = {
    val objectLocation = digestFile.path.toObjectLocation(bagLocation)
    for {
      inputStream <- Try {
        s3Client
          .getObject(objectLocation.namespace, objectLocation.key)
          .getObjectContent
      }.toEither.left.map(e =>
        FailedVerification(digestFile = digestFile, reason = e))

      actualChecksum <- ChecksumVerifier
        .checksum(
          inputStream,
          algorithm = algorithm
        )
        .toEither
        .left
        .map(e => FailedVerification(digestFile = digestFile, reason = e))

      result <- getResult(digestFile, actualChecksum = actualChecksum)
    } yield result
  }

  private def getResult(
    digestFile: BagDigestFile,
    actualChecksum: String): Either[FailedVerification, BagDigestFile] =
    if (digestFile.checksum == actualChecksum) {
      Right(digestFile)
    } else {
      Left(
        FailedVerification(
          digestFile = digestFile,
          reason = new RuntimeException(
            s"Checksums do not match: expected ${digestFile.checksum}, actually saw $actualChecksum")
        ))
    }
}
