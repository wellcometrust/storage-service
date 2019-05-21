package uk.ac.wellcome.platform.archive.common.fixtures

import uk.ac.wellcome.fixtures.TestWith
import uk.ac.wellcome.platform.archive.common.bagit.models.BagInfo
import uk.ac.wellcome.platform.archive.common.generators.{BagInfoGenerators, StorageSpaceGenerators}
import uk.ac.wellcome.platform.archive.common.storage.models.StorageSpace
import uk.ac.wellcome.storage.ObjectLocation
import uk.ac.wellcome.storage.SerialisationStrategy.stringStrategy
import uk.ac.wellcome.storage.memory.MemoryStorageBackend

trait BagLocationFixtures
    extends BagInfoGenerators
    with BagIt
    with StorageSpaceGenerators {

  def withBag[R](
    backend: MemoryStorageBackend,
    bagInfo: BagInfo = createBagInfo,
    dataFileCount: Int = 1,
    storageSpace: StorageSpace = createStorageSpace,
    createDataManifest: List[(String, String)] => Option[FileEntry] =
      createValidDataManifest,
    createTagManifest: List[(String, String)] => Option[FileEntry] =
      createValidTagManifest,
    bagRootDirectory: Option[String] = None)(
    testWith: TestWith[(ObjectLocation, StorageSpace), R]): R = {
    val bagIdentifier = createExternalIdentifier

    info(s"Creating Bag $bagIdentifier")

    val fileEntries = createBag(
      bagInfo,
      dataFileCount = dataFileCount,
      createDataManifest = createDataManifest,
      createTagManifest = createTagManifest)

    debug(s"fileEntries: $fileEntries")

    val storageSpaceRootLocation = ObjectLocation(
      namespace = "BagLocationFixtures",
      key = storageSpace.toString
    )

    val bagRootLocation = storageSpaceRootLocation.join(
      bagIdentifier.toString
    )

    val unpackedBagLocation = bagRootLocation.join(
      bagRootDirectory.getOrElse("")
    )

    fileEntries.map((entry: FileEntry) => {
      val entryLocation = unpackedBagLocation.join(entry.name)
      backend.put(
        location = entryLocation,
        input = stringStrategy.toStream(entry.contents).inputStream,
        metadata = Map.empty
      )
    })

    testWith((bagRootLocation, storageSpace))
  }
}
