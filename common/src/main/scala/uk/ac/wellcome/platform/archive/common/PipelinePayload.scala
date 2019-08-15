package uk.ac.wellcome.platform.archive.common

import java.time.Instant

import uk.ac.wellcome.platform.archive.common.bagit.models.{
  BagVersion,
  ExternalIdentifier
}
import uk.ac.wellcome.platform.archive.common.ingests.models.{
  Ingest,
  IngestID,
  IngestType,
  SourceLocation
}
import uk.ac.wellcome.platform.archive.common.storage.models.StorageSpace
import uk.ac.wellcome.storage.ObjectLocationPrefix

sealed trait PipelinePayload {
  val context: PipelineContext

  def ingestId: IngestID = context.ingestId
  def ingestType: IngestType = context.ingestType
  def storageSpace: StorageSpace = context.space
  def ingestDate: Instant = context.ingestDate
  def externalIdentifier: ExternalIdentifier = context.externalIdentifier
}

case class SourceLocationPayload(
  context: PipelineContext,
  sourceLocation: SourceLocation
) extends PipelinePayload

case object SourceLocationPayload {
  def apply(ingest: Ingest): SourceLocationPayload =
    SourceLocationPayload(
      context = PipelineContext(ingest),
      sourceLocation = ingest.sourceLocation
    )
}

case class UnpackedBagRootPayload(
  context: PipelineContext,
  unpackedBagRoot: ObjectLocationPrefix
) extends PipelinePayload

sealed trait BagRootPayload extends PipelinePayload {
  val bagRoot: ObjectLocationPrefix
}

case class BagRootLocationPayload(
  context: PipelineContext,
  bagRoot: ObjectLocationPrefix
) extends BagRootPayload

case class EnrichedBagInformationPayload(
  context: PipelineContext,
  bagRoot: ObjectLocationPrefix,
  version: BagVersion
) extends BagRootPayload
