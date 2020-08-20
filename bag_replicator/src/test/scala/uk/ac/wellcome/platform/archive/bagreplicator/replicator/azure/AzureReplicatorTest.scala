package uk.ac.wellcome.platform.archive.bagreplicator.replicator.azure

import uk.ac.wellcome.fixtures.TestWith
import uk.ac.wellcome.platform.archive.bagreplicator.replicator.ReplicatorTestCases
import uk.ac.wellcome.storage.azure.{AzureBlobLocation, AzureBlobLocationPrefix}
import uk.ac.wellcome.storage.fixtures.AzureFixtures
import uk.ac.wellcome.storage.fixtures.AzureFixtures.Container
import uk.ac.wellcome.storage.store.azure.AzureTypedStore
import uk.ac.wellcome.storage.streaming.Codec._
import uk.ac.wellcome.storage.tags.Tags
import uk.ac.wellcome.storage.tags.azure.AzureBlobMetadata
import uk.ac.wellcome.storage.transfer.azure.S3toAzurePrefixTransfer

class AzureReplicatorTest
    extends ReplicatorTestCases[
      Container,
      AzureBlobLocation,
      AzureBlobLocationPrefix,
      S3toAzurePrefixTransfer
    ]
    with AzureFixtures {

  override def withDstNamespace[R](testWith: TestWith[Container, R]): R =
    withAzureContainer { container =>
      testWith(container)
    }

  override def withPrefixTransfer[R](
    testWith: TestWith[S3toAzurePrefixTransfer, R]
  ): R =
    testWith(S3toAzurePrefixTransfer())

  override def withReplicator[R](
    prefixTransferImpl: S3toAzurePrefixTransfer
  )(testWith: TestWith[ReplicatorImpl, R]): R =
    testWith(new AzureReplicator() {
      override val prefixTransfer: S3toAzurePrefixTransfer = prefixTransferImpl
    })

  override def withReplicator[R](testWith: TestWith[ReplicatorImpl, R]): R =
    testWith(new AzureReplicator())

  override def createDstLocationWith(
    dstContainer: Container,
    name: String
  ): AzureBlobLocation =
    AzureBlobLocation(dstContainer.name, name = name)

  override def createDstPrefixWith(
    dstContainer: Container
  ): AzureBlobLocationPrefix =
    AzureBlobLocationPrefix(dstContainer.name, namePrefix = "")

  override val dstStringStore: AzureTypedStore[String] =
    AzureTypedStore[String]

  override val dstTags: Tags[AzureBlobLocation] = new AzureBlobMetadata()
}
