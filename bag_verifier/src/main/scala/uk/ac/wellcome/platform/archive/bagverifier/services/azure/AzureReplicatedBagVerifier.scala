package uk.ac.wellcome.platform.archive.bagverifier.services.azure

import com.amazonaws.services.s3.AmazonS3
import com.azure.storage.blob.BlobServiceClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import uk.ac.wellcome.platform.archive.bagverifier.fixity.FixityListChecker
import uk.ac.wellcome.platform.archive.bagverifier.fixity.azure.AzureFixityChecker
import uk.ac.wellcome.platform.archive.bagverifier.fixity.s3.S3FixityChecker
import uk.ac.wellcome.platform.archive.bagverifier.services.ReplicatedBagVerifier
import uk.ac.wellcome.platform.archive.bagverifier.storage.Resolvable
import uk.ac.wellcome.platform.archive.bagverifier.storage.azure.AzureResolvable
import uk.ac.wellcome.platform.archive.common.bagit.models.Bag
import uk.ac.wellcome.platform.archive.common.bagit.services.BagReader
import uk.ac.wellcome.platform.archive.common.bagit.services.azure.AzureBagReader
import uk.ac.wellcome.storage.azure.{AzureBlobLocation, AzureBlobLocationPrefix}
import uk.ac.wellcome.storage.dynamo.DynamoConfig
import uk.ac.wellcome.storage.listing.Listing
import uk.ac.wellcome.storage.listing.azure.AzureBlobLocationListing
import uk.ac.wellcome.storage.s3.S3ObjectLocation
import uk.ac.wellcome.storage.store.StreamStore
import uk.ac.wellcome.storage.store.azure.AzureStreamStore
import uk.ac.wellcome.storage.store.s3.S3StreamStore

class AzureReplicatedBagVerifier(
  val primaryBucket: String,
  val bagReader: BagReader[AzureBlobLocation, AzureBlobLocationPrefix],
  val listing: Listing[AzureBlobLocationPrefix, AzureBlobLocation],
  val resolvable: Resolvable[AzureBlobLocation],
  val fixityListChecker: FixityListChecker[
    AzureBlobLocation,
    AzureBlobLocationPrefix,
    Bag
  ],
  val srcReader: StreamStore[S3ObjectLocation],
  val streamReader: StreamStore[AzureBlobLocation]
) extends ReplicatedBagVerifier[AzureBlobLocation, AzureBlobLocationPrefix] {

  override def getRelativePath(
    root: AzureBlobLocationPrefix,
    location: AzureBlobLocation
  ): String =
    location.name.replace(root.namePrefix, "")
}

object AzureReplicatedBagVerifier {
  def apply(primaryBucket: String, dynamoConfig: DynamoConfig)(
    implicit s3Client: AmazonS3,
    blobClient: BlobServiceClient,
    dynamoClient: DynamoDbClient
  ): AzureReplicatedBagVerifier = {
    val bagReader = AzureBagReader()
    val listing = AzureBlobLocationListing()
    val resolvable = new AzureResolvable()
    implicit val fixityChecker = AzureFixityChecker(dynamoConfig)
    implicit val fetchDirectoryFixityChecker = S3FixityChecker()
    val srcReader = new S3StreamStore()
    val fixityListChecker =
      new FixityListChecker[AzureBlobLocation, AzureBlobLocationPrefix, Bag]()
    new AzureReplicatedBagVerifier(
      primaryBucket,
      bagReader,
      listing,
      resolvable,
      fixityListChecker,
      srcReader,
      streamReader = new AzureStreamStore()
    )
  }
}
