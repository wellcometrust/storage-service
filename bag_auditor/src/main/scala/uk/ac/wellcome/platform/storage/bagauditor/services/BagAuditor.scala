package uk.ac.wellcome.platform.storage.bagauditor.services

import java.time.Instant

import com.amazonaws.services.s3.AmazonS3
import uk.ac.wellcome.platform.archive.common.bagit.models.{
  BagInfo,
  ExternalIdentifier
}
import uk.ac.wellcome.platform.archive.common.ingests.models.IngestID
import uk.ac.wellcome.platform.archive.common.storage.StreamUnavailable
import uk.ac.wellcome.platform.archive.common.storage.models.{
  IngestFailed,
  IngestStepResult,
  IngestStepSucceeded,
  StorageSpace
}
import uk.ac.wellcome.platform.storage.bagauditor.models._
import uk.ac.wellcome.platform.archive.common.storage.services.S3BagLocator
import uk.ac.wellcome.platform.archive.common.storage.services.S3StreamableInstances._
import uk.ac.wellcome.platform.storage.bagauditor.versioning.VersionPicker
import uk.ac.wellcome.storage.ObjectLocation

import scala.util.{Failure, Success, Try}

class BagAuditor(versionPicker: VersionPicker)(implicit s3Client: AmazonS3) {
  val s3BagLocator = new S3BagLocator(s3Client)

  type IngestStep = Try[IngestStepResult[AuditSummary]]

  def getAuditSummary(ingestId: IngestID,
                      ingestDate: Instant,
                      root: ObjectLocation,
                      storageSpace: StorageSpace): IngestStep =
    Try {
      val startTime = Instant.now()

      val auditTry: Try[AuditSuccess] = for {
        externalIdentifier <- getBagIdentifier(root)
        version <- versionPicker.chooseVersion(
          externalIdentifier = externalIdentifier,
          ingestId = ingestId,
          ingestDate = ingestDate
        )
        auditSuccess = AuditSuccess(
          externalIdentifier = externalIdentifier,
          version = version
        )
      } yield auditSuccess

      val audit = auditTry match {
        case Success(auditSuccess) => auditSuccess
        case Failure(err) => AuditFailure(err)
      }

      audit match {
        case auditSuccess @ AuditSuccess(_, _) =>
          IngestStepSucceeded(
            AuditSuccessSummary(
              root = root,
              space = storageSpace,
              startTime = startTime,
              audit = auditSuccess,
              endTime = Some(Instant.now())
            )
          )
        case AuditFailure(err, userMessage) =>
          IngestFailed(
            AuditFailureSummary(
              root = root,
              space = storageSpace,
              startTime = startTime,
              endTime = Some(Instant.now())
            ),
            err,
            maybeUserFacingMessage = userMessage
          )
      }
    }

  private def getBagIdentifier(
    bagRootLocation: ObjectLocation): Try[ExternalIdentifier] =
    for {
      bagInfoLocation <- s3BagLocator.locateBagInfo(bagRootLocation)
      inputStream <- bagInfoLocation.toInputStream match {
        case Left(e)                  => Failure(e)
        case Right(None)              => Failure(StreamUnavailable("No stream available!"))
        case Right(Some(inputStream)) => Success(inputStream)
      }
      bagInfo <- BagInfo.create(inputStream)
    } yield bagInfo.externalIdentifier
}
