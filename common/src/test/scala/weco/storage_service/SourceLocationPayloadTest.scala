package weco.storage_service

import java.time.Instant

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import weco.storage_service.generators.{
  ExternalIdentifierGenerators,
  StorageSpaceGenerators
}
import weco.storage_service.ingests.models._
import weco.storage.fixtures.S3Fixtures

class SourceLocationPayloadTest
    extends AnyFunSpec
    with Matchers
    with ExternalIdentifierGenerators
    with StorageSpaceGenerators
    with S3Fixtures {

  it("creates a payload from an ingest") {
    val ingestId = createIngestID
    val ingestType = CreateIngestType
    val sourceLocation = createS3ObjectLocation
    val space = createStorageSpace
    val ingestDate = Instant.now()
    val externalIdentifier = createExternalIdentifier

    val ingest = Ingest(
      id = ingestId,
      ingestType = ingestType,
      sourceLocation = S3SourceLocation(
        location = sourceLocation
      ),
      space = space,
      createdDate = ingestDate,
      externalIdentifier = externalIdentifier,
      callback = None,
      status = Ingest.Accepted
    )

    val expectedPayload = SourceLocationPayload(
      context = PipelineContext(
        ingestId = ingestId,
        ingestType = ingestType,
        storageSpace = space,
        ingestDate = ingestDate,
        externalIdentifier = externalIdentifier
      ),
      sourceLocation = S3SourceLocation(sourceLocation)
    )

    SourceLocationPayload(ingest) shouldBe expectedPayload
  }
}
