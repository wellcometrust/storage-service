package uk.ac.wellcome.platform.archive.bagverifier.fixity.bag

import grizzled.slf4j.Logging
import uk.ac.wellcome.platform.archive.bagverifier.fixity.{
  CannotCreateExpectedFixity,
  ExpectedFileFixity,
  ExpectedFixity,
  FetchFileFixity,
  DataDirectoryFileFixity
}
import uk.ac.wellcome.platform.archive.bagverifier.storage.{
  Locatable,
  Resolvable
}
import uk.ac.wellcome.platform.archive.bagverifier.storage.bag.BagLocatable
import uk.ac.wellcome.platform.archive.common.bagit.models._
import uk.ac.wellcome.platform.archive.common.bagit.services.BagMatcher
import uk.ac.wellcome.platform.archive.common.verify._
import uk.ac.wellcome.storage.{Location, Prefix}

class BagExpectedFixity[BagLocation <: Location, BagPrefix <: Prefix[
  BagLocation
]](root: BagPrefix)(
  implicit resolvable: Resolvable[BagLocation]
) extends ExpectedFixity[Bag]
    with Logging {

  import BagLocatable._
  import Locatable._

  implicit val locatable: Locatable[BagLocation, BagPrefix, BagPath] =
    bagPathLocatable[BagLocation, BagPrefix]

  override def create(
    bag: Bag
  ): Either[CannotCreateExpectedFixity, Seq[ExpectedFileFixity]] = {
    debug(s"Attempting to get the fixity info for $bag")

    BagMatcher.correlateFetchEntries(bag) match {
      case Left(error) =>
        debug(s"Left: $error")
        Left(combine(Seq(error)))
      case Right(matched) =>
        debug(s"Right: $matched")

        val matches = matched.map(getVerifiableLocation)

        val failures = matches collect { case Left(f)           => f }
        val successes = matches collect { case Right(locations) => locations }

        debug(s"Got ($successes, $failures)")

        Either.cond(failures.isEmpty, successes, combine(failures))
    }
  }

  private def getVerifiableLocation(
    matched: MatchedLocation
  ): Either[Throwable, ExpectedFileFixity] =
    matched match {
      case MatchedLocation(
          bagPath: BagPath,
          checksum: Checksum,
          Some(fetchEntry)
          ) =>
        Right(
          FetchFileFixity(
            uri = fetchEntry.uri,
            path = bagPath,
            checksum = checksum,
            length = fetchEntry.length
          )
        )

      case MatchedLocation(bagPath: BagPath, checksum: Checksum, None) =>
        bagPath.locateWith(root) match {
          case Left(e) => Left(CannotCreateExpectedFixity(e.msg))
          case Right(location) =>
            Right(
              DataDirectoryFileFixity(
                uri = resolvable.resolve(location),
                path = bagPath,
                checksum = checksum
              )
            )
        }
    }

  private def combine(errors: Seq[Throwable]) =
    CannotCreateExpectedFixity(errors.map(_.getMessage).mkString("\n"))
}
