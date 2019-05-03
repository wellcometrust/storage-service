package uk.ac.wellcome.platform.storage.bagauditor.services
import java.time.Instant

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB
import com.amazonaws.services.dynamodbv2.document.internal.InternalUtils
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec
import com.amazonaws.services.dynamodbv2.document.{DynamoDB, Item}
import com.amazonaws.services.dynamodbv2.model.AttributeValue
import com.gu.scanamo.error.DynamoReadError
import com.gu.scanamo.{DynamoFormat, Scanamo, ScanamoFree, Table}
import uk.ac.wellcome.platform.archive.common.IngestID
import uk.ac.wellcome.platform.archive.common.bagit.models.ExternalIdentifier
import uk.ac.wellcome.storage.dynamo._

import scala.concurrent.Future

case class VersionRecord(
  ingestId: IngestID,
  ingestDate: Instant,
  externalIdentifier: String,
  version: Int
)

class VersionManager(
  dynamoClient: AmazonDynamoDB,
  dynamoConfig: DynamoConfig
) {

  val documentClient = new DynamoDB(dynamoClient)

  val table: Table[VersionRecord] = Table[VersionRecord](dynamoConfig.table)

  def assignVersion(
    ingestId: IngestID,
    ingestDate: Instant,
    externalIdentifier: ExternalIdentifier
  ): Future[Int] = {
    val newVersion = getLatestVersionRecord(externalIdentifier) match {
      case Some(existingRecord) => existingRecord.version + 1
      case None => 1
    }

    val newRecord = VersionRecord(
      ingestId = ingestId,
      ingestDate = ingestDate,
      externalIdentifier = externalIdentifier.underlying,
      version = newVersion
    )

    Scanamo.exec(dynamoClient)(table.put(newRecord))

    Future.successful(newVersion)
  }

  /** Find the existing version record with the highest version, or None
    * if this external ID hasn't been seen before.
    *
    */
  private def getLatestVersionRecord(externalIdentifier: ExternalIdentifier): Option[VersionRecord] = {

    // Query results are sorted by the range key, which in our case is `version`.
    // By setting `ScanIndexForward` to false, we sort the results in descending order,
    // so the first result returned is the highest version.
    val querySpec = new QuerySpec()
      .withHashKey("externalIdentifier", externalIdentifier.underlying)
      .withConsistentRead(true)
      .withScanIndexForward(false)
      .withMaxResultSize(1)

    val result = documentClient
      .getTable(dynamoConfig.table)
      .query(querySpec)
      .iterator()

    if (result.hasNext) {
      val item: Item = result.next()
      asCaseClass[VersionRecord](item) match {
        case Right(record) => Some(record)
        case Left(error) => throw new RuntimeException(s"Error parsing $item with Scanamo: $error")
      }
    } else {
      None
    }
  }

  private def asCaseClass[T](item: Item)(implicit evidence: DynamoFormat[T]): Either[DynamoReadError, T] = {
    val attributeValueMap: java.util.Map[String, AttributeValue] = InternalUtils.toAttributeValues(item)
    ScanamoFree.read[T](attributeValueMap)
  }
}
