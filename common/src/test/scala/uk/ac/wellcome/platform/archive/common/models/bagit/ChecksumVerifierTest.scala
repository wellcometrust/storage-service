package uk.ac.wellcome.platform.archive.common.models.bagit

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.AmazonS3Exception
import org.scalatest.{FunSpec, Matchers}
import uk.ac.wellcome.storage.ObjectLocation
import uk.ac.wellcome.storage.fixtures.S3

class ChecksumVerifierTest extends FunSpec with Matchers with S3 {
  implicit val s3client: AmazonS3 = s3Client

  it("calculates the checksum") {
    withLocalS3Bucket { bucket =>
      val content = "text"
      val key = "key"

      s3Client.putObject(bucket.name, key, content)

      val expectedChecksum =
        "982d9e3eb996f559e633f4d194def3761d909f5a3b647d1a851fead67c32c9d1"

      val actualChecksumEither = ChecksumVerifier.checksum(
        ObjectLocation(bucket.name, key),
        bagItAlgorithm = "sha256"
      )

      actualChecksumEither shouldBe a[Right[_, _]]
      val actualChecksum = actualChecksumEither.right.get

      actualChecksum shouldBe expectedChecksum
    }
  }

  it("returns Left for an unknown algorithm") {
    val actualChecksumEither = ChecksumVerifier.checksum(
      ObjectLocation("bucket", "key"),
      bagItAlgorithm = "unknown"
    )

    actualChecksumEither shouldBe a[Left[_, _]]

    actualChecksumEither.left.get shouldBe a[RuntimeException]
  }

  it("returns Left if the bucket cannot be found") {
    val actualChecksumEither = ChecksumVerifier.checksum(
      ObjectLocation("bucket", "not-there"),
      bagItAlgorithm = "sha256"
    )

    actualChecksumEither shouldBe a[Left[_, _]]

    actualChecksumEither.left.get shouldBe a[AmazonS3Exception]
  }

  it("returns Left if the object cannot be found") {
    withLocalS3Bucket { bucket =>
      val actualChecksumEither = ChecksumVerifier.checksum(
        ObjectLocation(bucket.name, "not-there"),
        bagItAlgorithm = "sha256"
      )

      actualChecksumEither shouldBe a[Left[_, _]]

      actualChecksumEither.left.get shouldBe a[AmazonS3Exception]
    }
  }
}
