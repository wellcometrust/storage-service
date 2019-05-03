package uk.ac.wellcome.platform.storage.bagauditor.services

import java.time.Instant

import com.amazonaws.services.dynamodbv2.model._
import com.amazonaws.services.dynamodbv2.util.TableUtils.waitUntilActive
import com.gu.scanamo.Scanamo
import com.gu.scanamo.error.DynamoReadError
import com.gu.scanamo.syntax._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{Assertion, FunSpec, Matchers}
import uk.ac.wellcome.fixtures.TestWith
import uk.ac.wellcome.platform.archive.common.IngestID
import uk.ac.wellcome.platform.archive.common.bagit.models.ExternalIdentifier
import uk.ac.wellcome.platform.archive.common.generators.ExternalIdentifierGenerators
import uk.ac.wellcome.storage.dynamo._
import uk.ac.wellcome.storage.fixtures.LocalDynamoDb
import uk.ac.wellcome.storage.fixtures.LocalDynamoDb.Table

case class BagVersion(
  ingestId: IngestID,
  ingestDate: Instant,
  externalIdentifier: ExternalIdentifier,
  version: Int
)

trait VersionManagerFixtures extends LocalDynamoDb {
  def withVersionManager[R](table: Table)(testWith: TestWith[VersionManager, R]): R = {
    val versionManager = new VersionManager()

    testWith(versionManager)
  }

  def createTable(table: Table): Table = {
    dynamoDbClient.createTable(
      new CreateTableRequest()
        .withTableName(table.name)
        .withKeySchema(new KeySchemaElement()
          .withAttributeName("externalIdentifier")
          .withKeyType(KeyType.HASH)
        )
        .withKeySchema(new KeySchemaElement()
          .withAttributeName("version")
          .withKeyType(KeyType.RANGE)
        )
        .withAttributeDefinitions(
          new AttributeDefinition()
            .withAttributeName("externalIdentifier")
            .withAttributeType("S"),
          new AttributeDefinition()
            .withAttributeName("version")
            .withAttributeType("N")
        )
        .withProvisionedThroughput(new ProvisionedThroughput()
          .withReadCapacityUnits(1L)
          .withWriteCapacityUnits(1L)
        )
    )

    eventually {
      waitUntilActive(dynamoDbClient, table.name)
    }

    table
  }

  def assertTableHasBagVersion(table: Table, bagVersion: BagVersion): Assertion = {
    val result: Option[Either[DynamoReadError, BagVersion]] =
      Scanamo.get[BagVersion](dynamoDbClient)(table.name)(
        'externalIdentifier -> bagVersion.externalIdentifier and
        'version -> bagVersion.version)

    result.get.right.get shouldBe bagVersion
  }
}

class VersionManagerTest extends FunSpec with Matchers with ScalaFutures with ExternalIdentifierGenerators with VersionManagerFixtures {
  it("assigns v1 for an external ID/ingest ID it's never seen before") {
    withLocalDynamoDbTable { versionTable =>
      withVersionManager(versionTable) { versionManager =>
        val future = versionManager.assignVersion(
          ingestId = createIngestID,
          ingestDate = Instant.now(),
          externalIdentifier = createExternalIdentifier
        )

        whenReady(future) { version =>
          version shouldBe 1
        }
      }
    }
  }

  it("records a version in DynamoDB") {
    withLocalDynamoDbTable { versionTable =>
      withVersionManager(versionTable) { versionManager =>
        val ingestId = createIngestID
        val ingestDate = Instant.now()
        val externalIdentifier = createExternalIdentifier

        assertTableEmpty[BagVersion](versionTable)

        val future = versionManager.assignVersion(
          ingestId = ingestId,
          ingestDate = ingestDate,
          externalIdentifier = externalIdentifier
        )

        whenReady(future) { version =>
          val expectedBagVersion = BagVersion(
            ingestId = ingestId,
            ingestDate = ingestDate,
            externalIdentifier = externalIdentifier,
            version = 1
          )

          assertTableHasBagVersion(versionTable, expectedBagVersion)
        }
      }
    }
  }
}
