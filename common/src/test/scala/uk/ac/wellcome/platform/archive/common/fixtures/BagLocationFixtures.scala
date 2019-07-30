package uk.ac.wellcome.platform.archive.common.fixtures

import java.net.URI

import uk.ac.wellcome.fixtures.TestWith
import uk.ac.wellcome.platform.archive.common.bagit.models.{
  BagFetch,
  BagFetchEntry,
  BagInfo,
  BagPath,
  ExternalIdentifier,
  PayloadOxum
}
import uk.ac.wellcome.platform.archive.common.generators.StorageSpaceGenerators
import uk.ac.wellcome.platform.archive.common.storage.models.StorageSpace
import uk.ac.wellcome.storage.ObjectLocation
import uk.ac.wellcome.storage.generators.ObjectLocationGenerators
import uk.ac.wellcome.storage.store.{TypedStore, TypedStoreEntry}

import scala.util.Random

trait BagLocationFixtures[Namespace]
    extends BagIt
    with StorageSpaceGenerators
    with ObjectLocationGenerators {
  def createObjectLocationWith(namespace: Namespace,
                               path: String): ObjectLocation

  def withBag[R](
    externalIdentifier: ExternalIdentifier = createExternalIdentifier,
    payloadOxum: Option[PayloadOxum] = None,
    dataFileCount: Int = 1,
    space: StorageSpace = createStorageSpace,
    createDataManifest: List[(String, String)] => Option[FileEntry] =
      createValidDataManifest,
    createTagManifest: List[(String, String)] => Option[FileEntry] =
      createValidTagManifest,
    bagRootDirectory: Option[String] = None)(
    testWith: TestWith[(ObjectLocation, BagInfo), R])(
    implicit typedStore: TypedStore[ObjectLocation, String],
    namespace: Namespace
  ): R = {
    info(s"Creating Bag $externalIdentifier")

    val (fileEntries, bagInfo) = createBag(
      payloadOxum = payloadOxum,
      externalIdentifier = externalIdentifier,
      dataFileCount = dataFileCount,
      createDataManifest = createDataManifest,
      createTagManifest = createTagManifest
    )

    debug(s"fileEntries: $fileEntries")

    val storageSpaceRootLocation = createObjectLocationWith(
      namespace,
      path = space.toString
    )

    val bagRootLocation = storageSpaceRootLocation.join(
      externalIdentifier.toString
    )

    val unpackedBagLocation = bagRootLocation.join(
      bagRootDirectory.getOrElse("")
    )

    // To simulate a bag that contains both concrete objects and
    // fetch files, siphon off some of the entries to be written
    // as a fetch file.
    //
    // We need to make sure bag-info.txt, manifest.txt, and so on,
    // are written into the bag proper.
    val (realFiles, fetchFiles) = fileEntries.partition { file =>
      file.name.endsWith(".txt") || Random.nextFloat() < 0.8
    }

    realFiles.map { entry =>
      val entryLocation = unpackedBagLocation.join(entry.name)

      typedStore.put(entryLocation)(TypedStoreEntry(
        entry.contents,
        metadata = Map.empty)) shouldBe a[Right[_, _]]
    }

    val bagFetchEntries = fetchFiles.map { entry =>
      val entryLocation = createObjectLocationWith(
        namespace,
        path = randomAlphanumeric
      )

      typedStore.put(entryLocation)(TypedStoreEntry(
        entry.contents,
        metadata = Map.empty)) shouldBe a[Right[_, _]]

      BagFetchEntry(
        uri = new URI(s"s3://${entryLocation.namespace}/${entryLocation.path}"),
        length = Some(entry.contents.length),
        path = BagPath(entry.name)
      )
    }

    if (fetchFiles.nonEmpty) {
      val fetchLocation = unpackedBagLocation.join("fetch.txt")
      val fetchContents = BagFetch.write(bagFetchEntries)

      typedStore.put(fetchLocation)(TypedStoreEntry(
        fetchContents,
        metadata = Map.empty)) shouldBe a[Right[_, _]]
    }

    testWith((bagRootLocation, bagInfo))
  }
}
