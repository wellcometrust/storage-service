package uk.ac.wellcome.platform.archive.bagunpacker

import java.nio.file.Paths

import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{FunSpec, Matchers}
import uk.ac.wellcome.json.JsonUtil._
import uk.ac.wellcome.platform.archive.bagunpacker.fixtures.{
  BagUnpackerFixtures,
  CompressFixture
}
import uk.ac.wellcome.platform.archive.common.UnpackedBagPayload
import uk.ac.wellcome.platform.archive.common.fixtures.RandomThings
import uk.ac.wellcome.platform.archive.common.generators.PayloadGenerators
import uk.ac.wellcome.platform.archive.common.ingests.fixtures.IngestUpdateAssertions
import uk.ac.wellcome.platform.archive.common.ingests.models.Ingest

class UnpackerFeatureTest
    extends FunSpec
    with Matchers
    with ScalaFutures
    with RandomThings
    with BagUnpackerFixtures
    with IntegrationPatience
    with CompressFixture
    with IngestUpdateAssertions
    with PayloadGenerators {

  it("receives and processes a notification") {
    val (archiveFile, _, _) = createTgzArchiveWithRandomFiles()
    withBagUnpackerApp {
      case (_, srcBucket, queue, ingestTopic, outgoingTopic) =>
        withArchive(srcBucket, archiveFile) { archiveLocation =>
          val ingestRequestPayload =
            createIngestRequestPayloadWith(archiveLocation)
          sendNotificationToSQS(queue, ingestRequestPayload)

          eventually {
            val expectedPayload = UnpackedBagPayload(
              ingestRequestPayload = ingestRequestPayload,
              unpackedBagLocation = createObjectLocationWith(
                bucket = srcBucket,
                key = Paths
                  .get(
                    ingestRequestPayload.storageSpace.toString,
                    ingestRequestPayload.ingestId.toString
                  )
                  .toString
              )
            )

            assertSnsReceivesOnly(expectedPayload, outgoingTopic)

            assertTopicReceivesIngestEvent(
              ingestId = ingestRequestPayload.ingestId,
              ingestTopic = ingestTopic
            ) { events =>
              events.map {
                _.description
              } shouldBe List(
                "Unpacker succeeded"
              )
            }
          }
        }
    }
  }

  it("sends a failed Ingest update if it cannot read the bag") {
    withBagUnpackerApp {
      case (_, _, queue, ingestTopic, outgoingTopic) =>
        val payload = createIngestRequestPayload
        sendNotificationToSQS(queue, payload)

        eventually {
          assertSnsReceivesNothing(outgoingTopic)

          assertTopicReceivesIngestStatus(
            ingestId = payload.ingestId,
            ingestTopic = ingestTopic,
            status = Ingest.Failed
          ) { events =>
            events.map { _.description }.distinct shouldBe
              List(
                s"Unpacker failed - ${payload.sourceLocation} does not exist")
          }
        }
    }
  }
}
