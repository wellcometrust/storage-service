package uk.ac.wellcome.platform.archive.bagreplicator

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FunSpec, Matchers}
import uk.ac.wellcome.json.JsonUtil._
import uk.ac.wellcome.platform.archive.bagreplicator.fixtures.{
  BagReplicatorFixtures,
  WorkerServiceFixture
}
import uk.ac.wellcome.platform.archive.common.fixtures.BagLocationFixtures
import uk.ac.wellcome.platform.archive.common.generators.BagRequestGenerators
import uk.ac.wellcome.platform.archive.common.models.BagRequest
import uk.ac.wellcome.platform.archive.common.models.bagit.{
  BagLocation,
  BagPath
}
import uk.ac.wellcome.platform.archive.common.progress.ProgressUpdateAssertions

class BagReplicatorFeatureTest
    extends FunSpec
    with Matchers
    with ScalaFutures
    with BagLocationFixtures
    with BagReplicatorFixtures
    with BagRequestGenerators
    with ProgressUpdateAssertions
    with WorkerServiceFixture {

  it("replicates a bag successfully and updates both topics") {
    withLocalS3Bucket { ingestsBucket =>
      withLocalS3Bucket { archiveBucket =>
        val destination = createReplicatorDestinationConfigWith(archiveBucket)

        withLocalSqsQueue { queue =>
          withLocalSnsTopic { progressTopic =>
            withLocalSnsTopic { outgoingTopic =>
              withWorkerService(
                queue,
                progressTopic = progressTopic,
                outgoingTopic = outgoingTopic,
                destination = destination) { _ =>
                val bagInfo = createBagInfo

                withBag(ingestsBucket, bagInfo = bagInfo) { srcBagLocation =>
                  val bagRequest = createBagRequestWith(srcBagLocation)

                  sendNotificationToSQS(queue, bagRequest)

                  eventually {
                    val result = notificationMessage[BagRequest](outgoingTopic)
                    result.requestId shouldBe bagRequest.requestId

                    val dstBagLocation = result.bagLocation

                    dstBagLocation shouldBe BagLocation(
                      storageNamespace = destination.namespace,
                      storagePrefix = destination.rootPath,
                      storageSpace = srcBagLocation.storageSpace,
                      bagPath = BagPath(bagInfo.externalIdentifier.underlying)
                    )

                    verifyBagCopied(
                      src = srcBagLocation,
                      dst = dstBagLocation
                    )

                    assertTopicReceivesProgressEventUpdate(
                      bagRequest.requestId,
                      progressTopic) { events =>
                      events should have size 1
                      events.head.description shouldBe "Replicating succeeded"
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
