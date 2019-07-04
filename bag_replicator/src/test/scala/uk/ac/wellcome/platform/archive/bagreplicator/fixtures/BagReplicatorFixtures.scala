package uk.ac.wellcome.platform.archive.bagreplicator.fixtures

import java.util.UUID

import com.amazonaws.services.s3.model.S3ObjectSummary
import org.scalatest.Assertion
import uk.ac.wellcome.fixtures.TestWith
import uk.ac.wellcome.messaging.fixtures.SQS.Queue
import uk.ac.wellcome.messaging.fixtures.worker.AlpakkaSQSWorkerFixtures
import uk.ac.wellcome.messaging.memory.MemoryMessageSender
import uk.ac.wellcome.platform.archive.bagreplicator.config.ReplicatorDestinationConfig
import uk.ac.wellcome.platform.archive.common.fixtures.S3BagLocationFixtures
import uk.ac.wellcome.platform.archive.bagreplicator.services.{BagReplicator, BagReplicatorWorker}
import uk.ac.wellcome.platform.archive.common.fixtures.{MonitoringClientFixture, OperationFixtures}
import uk.ac.wellcome.platform.archive.common.storage.models.IngestStepResult
import uk.ac.wellcome.storage.ObjectLocation
import uk.ac.wellcome.storage.fixtures.S3Fixtures.Bucket
import uk.ac.wellcome.storage.locking.memory.{MemoryLockDao, MemoryLockDaoFixtures}
import uk.ac.wellcome.storage.locking.{LockDao, LockingService}

import scala.collection.JavaConverters._
import scala.util.{Random, Try}

import uk.ac.wellcome.json.JsonUtil._

trait BagReplicatorFixtures
    extends S3BagLocationFixtures
    with OperationFixtures
    with AlpakkaSQSWorkerFixtures
    with MonitoringClientFixture
    with MemoryLockDaoFixtures {

  def withBagReplicatorWorker[R](
    queue: Queue =
      Queue(randomAlphanumericWithLength(), randomAlphanumericWithLength()),
    bucket: Bucket = Bucket(randomAlphanumericWithLength()),
    rootPath: Option[String] = None,
    ingests: MemoryMessageSender = new MemoryMessageSender(),
    outgoing: MemoryMessageSender = new MemoryMessageSender(),
    lockServiceDao: LockDao[String, UUID] = new MemoryLockDao[String, UUID] {},
    stepName: String = randomAlphanumericWithLength())(
    testWith: TestWith[BagReplicatorWorker[String, String], R]
  ): R =
    withActorSystem { implicit actorSystem =>
      val ingestUpdater = createIngestUpdaterWith(ingests, stepName = stepName)
      val outgoingPublisher = createOutgoingPublisherWith(outgoing)
      withMonitoringClient { implicit monitoringClient =>
        val lockingService = new LockingService[
          IngestStepResult[ReplicationSummary],
          Try,
          LockDao[String, UUID]] {
          override implicit val lockDao: LockDao[String, UUID] =
            lockServiceDao
          override protected def createContextId(): lockDao.ContextId =
            UUID.randomUUID()
        }

        val replicatorDestinationConfig =
          createReplicatorDestinationConfigWith(bucket, rootPath)

        val service = new BagReplicatorWorker(
          config = createAlpakkaSQSWorkerConfig(queue),
          bagReplicator = new BagReplicator(),
          ingestUpdater = ingestUpdater,
          outgoingPublisher = outgoingPublisher,
          lockingService = lockingService,
          replicatorDestinationConfig = replicatorDestinationConfig
        )

        service.run()

        testWith(service)
      }
    }

  private def createReplicatorDestinationConfigWith(
    bucket: Bucket,
    rootPath: Option[String]
  ): ReplicatorDestinationConfig =
    ReplicatorDestinationConfig(
      namespace = bucket.name,
      rootPath = rootPath
    )

  // Note: the replicator doesn't currently make any assumptions about
  // the bag structure, so we just put a random collection of objects
  // in the "bag".
  def withBagObjects[R](bucket: Bucket)(
    testWith: TestWith[ObjectLocation, R]): R = {
    val rootLocation = createObjectLocationWith(bucket)

    (1 to 250).map { _ =>
      val parts = (1 to Random.nextInt(5)).map { _ =>
        randomAlphanumeric
      }

      val location = rootLocation.join(parts: _*)

      s3Client.putObject(
        location.namespace,
        location.path,
        randomAlphanumeric
      )
    }

    testWith(rootLocation)
  }

  def verifyObjectsCopied(src: ObjectLocation,
                          dst: ObjectLocation): Assertion = {
    val sourceItems = getObjectSummaries(src)
    val sourceKeyEtags = sourceItems.map { _.getETag }

    val destinationItems = getObjectSummaries(dst)
    val destinationKeyEtags = destinationItems.map { _.getETag }

    destinationKeyEtags should contain theSameElementsAs sourceKeyEtags
  }

  private def getObjectSummaries(
    objectLocation: ObjectLocation): List[S3ObjectSummary] =
    s3Client
      .listObjects(objectLocation.namespace, objectLocation.path)
      .getObjectSummaries
      .asScala
      .toList
}
