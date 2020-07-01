package uk.ac.wellcome.platform.archive.bagunpacker.services

import java.io.{File, FileInputStream}
import java.nio.file.Paths

import org.scalatest.{Assertion, TryValues}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import uk.ac.wellcome.fixtures.TestWith
import uk.ac.wellcome.platform.archive.bagunpacker.fixtures.CompressFixture
import uk.ac.wellcome.platform.archive.bagunpacker.models.UnpackSummary
import uk.ac.wellcome.platform.archive.common.storage.models.{IngestFailed, IngestStepSucceeded}
import uk.ac.wellcome.storage.store.StreamStore
import uk.ac.wellcome.storage.streaming.Codec._
import uk.ac.wellcome.storage.streaming.StreamAssertions
import uk.ac.wellcome.storage.{Location, Prefix}

trait UnpackerTestCases[
  BagLocation <: Location,
  BagPrefix <: Prefix[BagLocation],
  StoreImpl <: StreamStore[BagLocation],
  Namespace]
    extends AnyFunSpec
    with Matchers
    with TryValues
    with CompressFixture[BagLocation, Namespace]
    with StreamAssertions {
  def withUnpacker[R](testWith: TestWith[Unpacker[BagLocation, BagLocation, BagPrefix], R])(
    implicit streamStore: StoreImpl
  ): R

  private def withUnpackerAndStore[R](testWith: TestWith[Unpacker[BagLocation, BagLocation, BagPrefix], R]): R =
    withStreamStore { implicit streamStore =>
      withUnpacker { unpacker =>
        testWith(unpacker)
      }
    }

  def withNamespace[R](testWith: TestWith[Namespace, R]): R

  def withStreamStore[R](testWith: TestWith[StoreImpl, R]): R

  def createSrcLocationWith(namespace: Namespace, path: String = randomAlphanumeric): BagLocation
  def createDstPrefixWith(namespace: Namespace, pathPrefix: String = randomAlphanumeric): BagPrefix

  override def createLocationWith(namespace: Namespace, path: String): BagLocation =
    createSrcLocationWith(namespace = namespace, path = path)

  def createDstPrefix: BagPrefix =
    withNamespace { namespace =>
      createDstPrefixWith(namespace)
    }

  it("unpacks a tgz archive") {
    val (archiveFile, filesInArchive, _) = createTgzArchiveWithRandomFiles()

    withNamespace { srcNamespace =>
      withNamespace { dstNamespace =>
        withStreamStore { implicit streamStore =>
          withArchive(srcNamespace, archiveFile) { archiveLocation =>
            val dstPrefix = createDstPrefixWith(dstNamespace, pathPrefix = "unpacker")

            val summaryResult =
              withUnpacker {
                _.unpack(
                  ingestId = createIngestID,
                  srcLocation = archiveLocation,
                  dstPrefix = dstPrefix
                )
              }

            val unpacked = summaryResult.success.value
            unpacked shouldBe a[IngestStepSucceeded[_]]

            unpacked.maybeUserFacingMessage.get should fullyMatch regex
              """Unpacked \d+ [KM]B from \d+ files"""

            val summary = unpacked.summary
            summary.fileCount shouldBe filesInArchive.size
            summary.bytesUnpacked shouldBe totalBytes(filesInArchive)

            assertEqual(dstPrefix, filesInArchive)
          }
        }
      }
    }
  }

  it("normalizes file entries such as './' when unpacking") {
    val (archiveFile, filesInArchive, _) =
      createTgzArchiveWithFiles(
        randomFilesWithNames(
          List("./testA", "/testB", "/./testC", "//testD")
        )
      )

    withNamespace { srcNamespace =>
      withNamespace { dstNamespace =>
        withStreamStore { implicit streamStore =>
          withArchive(srcNamespace, archiveFile) { archiveLocation =>
            val dstPrefix = createDstPrefixWith(dstNamespace, pathPrefix = "unpacker")
            val summaryResult =
              withUnpacker {
                _.unpack(
                  ingestId = createIngestID,
                  srcLocation = archiveLocation,
                  dstPrefix = dstPrefix
                )
              }

            val unpacked = summaryResult.success.value
            unpacked shouldBe a[IngestStepSucceeded[_]]

            unpacked.maybeUserFacingMessage.get should fullyMatch regex
              """Unpacked \d+ [KM]B from \d+ files"""

            val summary = unpacked.summary
            summary.fileCount shouldBe filesInArchive.size
            summary.bytesUnpacked shouldBe totalBytes(filesInArchive)

            assertEqual(dstPrefix, filesInArchive)
          }
        }
      }
    }
  }

  it("fails if the original archive does not exist") {
    withNamespace { srcNamespace =>
      val srcLocation = createSrcLocationWith(srcNamespace)
      val result =
        withUnpackerAndStore {
          _.unpack(
            ingestId = createIngestID,
            srcLocation = srcLocation,
            dstPrefix = createDstPrefix
          )
        }

      val ingestResult = result.success.value
      ingestResult shouldBe a[IngestFailed[_]]
      ingestResult.summary.fileCount shouldBe 0
      ingestResult.summary.bytesUnpacked shouldBe 0

      val ingestFailed = ingestResult.asInstanceOf[IngestFailed[UnpackSummary[_, _]]]
      ingestFailed.maybeUserFacingMessage.get should startWith(
        "There is no archive at"
      )
    }
  }

  it("fails if the specified file is not in tar.gz format") {
    withNamespace { srcNamespace =>
      withStreamStore { implicit streamStore =>
        val srcLocation = createSrcLocationWith(namespace = srcNamespace)

        streamStore.put(srcLocation)(
          stringCodec.toStream("hello world").right.value
        ) shouldBe a[Right[_, _]]

        val result =
          withUnpacker {
            _.unpack(
              ingestId = createIngestID,
              srcLocation = srcLocation,
              dstPrefix = createDstPrefix
            )
          }

        val ingestResult = result.success.value
        ingestResult shouldBe a[IngestFailed[_]]
        ingestResult.summary.fileCount shouldBe 0
        ingestResult.summary.bytesUnpacked shouldBe 0

        val ingestFailed =
          ingestResult.asInstanceOf[IngestFailed[UnpackSummary[_, _]]]
        ingestFailed.maybeUserFacingMessage.get should startWith(
          s"Error trying to unpack the archive at"
        )
      }
    }
  }

  def assertEqual(prefix: BagPrefix, expectedFiles: Seq[File])(
    implicit store: StreamStore[BagLocation]
  ): Seq[Assertion] = {
    expectedFiles.map { file =>
      val name = Paths
        .get(relativeToTmpDir(file))
        .normalize()
        .toString

      val expectedLocation = prefix.asLocation(name)

      val originalContent = new FileInputStream(file)
      val storedContent = store.get(expectedLocation).right.value.identifiedT

      assertStreamsEqual(originalContent, storedContent)
    }
  }

  private def totalBytes(files: Seq[File]): Long =
    files
      .foldLeft(0L) { (n, file) =>
        n + file.length()
      }
}
