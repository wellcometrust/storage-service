package uk.ac.wellcome.platform.storage.bag_versioner.versioning.dynamo

import org.scanamo.syntax._
import org.scanamo.{DynamoFormat, Scanamo, Table => ScanamoTable}
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import uk.ac.wellcome.platform.archive.common.bagit.models.{
  BagId,
  ExternalIdentifier
}
import uk.ac.wellcome.platform.archive.common.ingests.models.IngestID
import uk.ac.wellcome.platform.archive.common.ingests.models.IngestID._
import uk.ac.wellcome.platform.archive.common.storage.models.StorageSpace
import uk.ac.wellcome.platform.storage.bag_versioner.versioning.{
  IngestVersionManagerDao,
  VersionRecord
}
import uk.ac.wellcome.storage.{MaximaError, MaximaReadError, NoMaximaValueError}
import uk.ac.wellcome.storage.dynamo._

import scala.util.{Failure, Success, Try}

class DynamoIngestVersionManagerDao(
  dynamoClient: DynamoDbClient,
  dynamoConfig: DynamoConfig
)(
  implicit
  formatVersionRecord: DynamoFormat[DynamoVersionRecord]
) extends IngestVersionManagerDao {

  private val scanamoTable =
    ScanamoTable[DynamoVersionRecord](dynamoConfig.tableName)
  private val index = scanamoTable.index(dynamoConfig.indexName)

  // TODO: Rewrite this to use Either
  override def lookupExistingVersion(
    ingestId: IngestID
  ): Try[Option[VersionRecord]] = {
    val ops = index.query("ingestId" === ingestId)

    Try { Scanamo(dynamoClient).exec(ops) } match {
      case Success(List(Right(record))) =>
        Success(Some(record.toVersionRecord))
      case Success(Nil) => Success(None)
      case Success(result) =>
        Failure(
          new RuntimeException(
            s"Did not find exactly one row with ingest ID $ingestId, got $result"
          )
        )

      case Failure(err) => Failure(err)
    }
  }

  override def lookupLatestVersionFor(
    externalIdentifier: ExternalIdentifier,
    storageSpace: StorageSpace
  ): Either[MaximaError, VersionRecord] = {
    val bagId = BagId(
      space = storageSpace,
      externalIdentifier = externalIdentifier
    )

    val ops = scanamoTable.descending
      .limit(1)
      .query("id" === bagId.toString)

    Try(Scanamo(dynamoClient).exec(ops)) match {
      case Success(List(Right(row: DynamoVersionRecord))) =>
        Right(row.toVersionRecord)
      case Success(List(Left(err))) =>
        val error = new Error(s"DynamoReadError: ${err.toString}")
        Left(MaximaReadError(error))
      case Success(Nil) => Left(NoMaximaValueError())
      case Failure(err) => Left(MaximaReadError(err))

      // This case should be impossible to hit in practice -- limit(1)
      // means we should only get a single result from DynamoDB.
      case result =>
        val error = new Error(
          s"Unknown error from Scanamo! $result"
        )
        Left(MaximaReadError(error))
    }
  }

  override def storeNewVersion(record: VersionRecord): Try[Unit] = {
    val ops = scanamoTable.put(DynamoVersionRecord(record))

    Try {
      Scanamo(dynamoClient).exec(ops)
    }
  }
}