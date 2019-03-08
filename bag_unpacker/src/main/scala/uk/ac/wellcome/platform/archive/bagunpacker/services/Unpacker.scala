package uk.ac.wellcome.platform.archive.bagunpacker.services

import java.io.InputStream
import java.nio.file.Paths
import java.time.Instant

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{ObjectMetadata, PutObjectRequest, PutObjectResult}
import org.apache.commons.compress.archivers.ArchiveEntry
import uk.ac.wellcome.platform.archive.bagunpacker.models.UnpackSummary
import uk.ac.wellcome.platform.archive.bagunpacker.storage.Archive
import uk.ac.wellcome.platform.archive.common.ConvertibleToInputStream._
import uk.ac.wellcome.platform.archive.common.operation.{
  OperationFailure,
  OperationResult
}
import uk.ac.wellcome.storage.ObjectLocation

import scala.concurrent.{ExecutionContext, Future}

class Unpacker(implicit s3Client: AmazonS3, ec: ExecutionContext) {

  def unpack(
    srcLocation: ObjectLocation,
    dstLocation: ObjectLocation
  ): Future[OperationResult[UnpackSummary]] = {

    val unpackSummary =
      UnpackSummary(startTime = Instant.now)

    val futureSummary = for {
      packageInputStream <- srcLocation.toInputStream
      result <- Archive.unpack(packageInputStream)(unpackSummary) {
        (summary: UnpackSummary,
         inputStream: InputStream,
         archiveEntry: ArchiveEntry) =>
          if (!archiveEntry.isDirectory) {
            val archiveEntrySize = putObject(
              inputStream,
              archiveEntry,
              dstLocation
            )

            summary.copy(
              fileCount = summary.fileCount + 1,
              bytesUnpacked = summary.bytesUnpacked + archiveEntrySize
            )
          } else {
            summary
          }

      }
    } yield result

    futureSummary
      .recover {
        case err: Throwable =>
          OperationFailure(unpackSummary.complete, err)
      }
  }

  private def putObject(
    inputStream: InputStream,
    archiveEntry: ArchiveEntry,
    destination: ObjectLocation
  ): Long = {
    val archiveEntrySize = archiveEntry.getSize

    if (archiveEntrySize == ArchiveEntry.SIZE_UNKNOWN) {
      throw new RuntimeException(
        s"Unknown entry size for ${archiveEntry.getName}!"
      )
    }

    val uploadLocation = destination.copy(
      key = normalizeKey(destination.key, archiveEntry.getName)
    )

    singlePartUpload(
      archiveEntrySize = archiveEntrySize,
      uploadLocation = uploadLocation,
      inputStream = inputStream
    )

    archiveEntrySize
  }

  private def singlePartUpload(archiveEntrySize: Long, uploadLocation: ObjectLocation, inputStream: InputStream): PutObjectResult = {
    val metadata = new ObjectMetadata()

    metadata.setContentLength(archiveEntrySize)

    val request =
      new PutObjectRequest(
        uploadLocation.namespace,
        uploadLocation.key,
        inputStream,
        metadata
      )

    s3Client.putObject(request)
  }

  private def normalizeKey(prefix: String, key: String) = {
    Paths
      .get(prefix, key)
      .normalize()
      .toString
  }
}
