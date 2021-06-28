package weco.storage_service.bag_unpacker.services

import java.io.{EOFException, IOException, InputStream}
import java.time.Instant
import grizzled.slf4j.Logging
import org.apache.commons.compress.archivers.ArchiveEntry
import weco.storage_service.bag_unpacker.models.UnpackSummary
import weco.storage_service.bag_unpacker.storage.{
  DuplicateArchiveEntryException,
  Unarchiver,
  UnexpectedUnarchiverError
}
import weco.storage_service.ingests.models.IngestID
import weco.storage_service.storage.models.{
  IngestFailed,
  IngestStepResult,
  IngestStepSucceeded
}
import weco.storage.store.{Readable, Writable}
import weco.storage.streaming.InputStreamWithLength
import weco.storage._

import scala.util.{Failure, Success, Try}

trait Unpacker[
  SrcLocation <: Location,
  DstLocation <: Location,
  DstPrefix <: Prefix[DstLocation]
] extends Logging {

  protected val reader: Readable[SrcLocation, InputStreamWithLength]
  protected val writer: Writable[DstLocation, InputStreamWithLength]

  // The unpacker asks for separate get/put methods rather than a Store
  // because it might be unpacking/uploading to different providers.
  //
  // e.g. we might unpack a package from an S3 bucket, then upload it to Azure.
  //
  def get(location: SrcLocation): Either[StorageError, InputStream] =
    reader.get(location).map { _.identifiedT }

  def put(location: DstLocation)(
    inputStream: InputStreamWithLength
  ): Either[StorageError, Unit] =
    writer
      .put(location)(inputStream)
      .map { _ =>
        ()
      }

  def unpack(
    ingestId: IngestID,
    srcLocation: SrcLocation,
    dstPrefix: DstPrefix
  ): Try[IngestStepResult[UnpackSummary[SrcLocation, DstPrefix]]] = {
    val unpackSummary =
      UnpackSummary(
        ingestId = ingestId,
        srcLocation = srcLocation,
        dstPrefix = dstPrefix,
        startTime = Instant.now
      )

    val result = for {
      srcStream <- get(srcLocation).left.map { storageError =>
        UnpackerStorageError(storageError)
      }

      unpackSummary <- unpack(unpackSummary, srcStream, dstPrefix)
    } yield unpackSummary

    result match {
      case Right(summary) =>
        Success(
          IngestStepSucceeded(
            summary,
            maybeUserFacingMessage = Some(UnpackerMessage.create(summary))
          )
        )

      case Left(unpackerError) =>
        Success(
          IngestFailed(
            unpackSummary,
            e = unpackerError.e,
            maybeUserFacingMessage = buildMessageFor(
              srcLocation,
              error = unpackerError
            )
          )
        )
    }
  }

  protected def buildMessageFor(
    srcLocation: SrcLocation,
    error: UnpackerError
  ): Option[String] =
    error match {
      case UnpackerStorageError(_: DoesNotExistError) =>
        Some(s"There is no archive at $srcLocation")

      case UnpackerUnarchiverError(_) =>
        Some(
          s"Error trying to unpack the archive at $srcLocation - is it the correct format?"
        )

      case UnpackerEOFError(_) =>
        Some(
          s"Unexpected EOF while unpacking the archive at $srcLocation - is it the correct format?"
        )

      case UnpackerUnexpectedError(err: DuplicateArchiveEntryException) =>
        Some(
          s"The archive at $srcLocation is malformed or has a duplicate entry (${err.entry.getName})"
        )

      case _ => None
    }

  private def unpack(
    unpackSummary: UnpackSummary[SrcLocation, DstPrefix],
    srcStream: InputStream,
    dstPrefix: DstPrefix
  ): Either[UnpackerError, UnpackSummary[SrcLocation, DstPrefix]] =
    Unarchiver.open(srcStream) match {
      case Left(unarchiverError) =>
        Left(UnpackerUnarchiverError(unarchiverError))

      case Right(iterator) =>
        // For large bags, the standard Int type can overflow and report a negative
        // number of bytes.  This is silly, so we ensure these are treated as Long.
        // See https://github.com/wellcometrust/platform/issues/3947
        var totalFiles: Long = 0
        var totalBytes: Long = 0

        Try {
          iterator
            .filterNot { case (archiveEntry, _) => archiveEntry.isDirectory }
            .foreach {
              case (archiveEntry, entryStream) =>
                debug(s"Processing archive entry ${archiveEntry.getName}")
                val uploadedBytes = putObject(
                  inputStream = entryStream,
                  archiveEntry = archiveEntry,
                  dstPrefix = dstPrefix
                )

                totalFiles += 1
                totalBytes += uploadedBytes
            }

          unpackSummary.copy(
            fileCount = totalFiles,
            bytesUnpacked = totalBytes
          )
        } match {
          case Success(result) => Right(result)
          case Failure(err)    => Left(handleError(err))
        }
    }

  protected def handleError(t: Throwable): UnpackerError =
    t match {
      case err: EOFException => UnpackerEOFError(err)
      case err: IOException
          if err.getMessage == "Error detected parsing the header" =>
        UnpackerUnarchiverError(UnexpectedUnarchiverError(err))
      case err: IOException if err.getMessage.startsWith("unexpected EOF") =>
        UnpackerEOFError(err)
      case err => UnpackerUnexpectedError(err)
    }

  private def putObject(
    inputStream: InputStream,
    archiveEntry: ArchiveEntry,
    dstPrefix: DstPrefix
  ): Long = {

    // Sometimes the entries in a tar.gz archive are prefixed with ./, for example:
    //
    //      ./PBLBIO/bag-info.txt
    //
    // We don't want to include the leading `./` in the names we write to the
    // unpacked bags bucket, because they can cause issues in the S3 console and the
    // root finder.
    //
    // We do this normalisation manually rather than normalising the whole string,
    // so that we can spot any weirdness with overlapping entries.  e.g.
    //
    //      my_bag/data/cat.jpg
    //      my_bag/data/pictures/../cat.jpg
    //
    // could be two distinct entries in the tar.gz, but different objects.  We don't
    // want to unpack them to the same object; we want to notice and throw an error.
    //
    val name = archiveEntry.getName.stripPrefix("./")
    val uploadLocation = dstPrefix.asLocation(name)

    val archiveEntrySize = archiveEntry.getSize

    if (archiveEntrySize == ArchiveEntry.SIZE_UNKNOWN) {
      throw new RuntimeException(
        s"Unknown entry size for ${archiveEntry.getName}!"
      )
    }

    debug(
      s"Uploading archive entry ${archiveEntry.getName} to $uploadLocation"
    )

    put(uploadLocation)(
      new InputStreamWithLength(inputStream, length = archiveEntrySize)
    ) match {
      case Right(_)           => ()
      case Left(storageError) => throw storageError.e
    }

    archiveEntrySize
  }
}