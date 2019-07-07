package uk.ac.wellcome.platform.archive.notifier.services

import java.net.URI

import akka.http.scaladsl.model.StatusCodes
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{FunSpec, Matchers}
import uk.ac.wellcome.akka.fixtures.Akka
import uk.ac.wellcome.platform.archive.common.generators.IngestGenerators
import uk.ac.wellcome.platform.archive.notifier.fixtures.{
  LocalWireMockFixture,
  NotifierFixtures
}

class CallbackUrlServiceTest
    extends FunSpec
    with Matchers
    with ScalaFutures
    with IntegrationPatience
    with Akka
    with NotifierFixtures
    with LocalWireMockFixture
    with IngestGenerators {

  it("returns a Success if the request succeeds") {
    withActorSystem { implicit actorSystem =>
      withCallbackUrlService { service =>
        val ingest = createIngest

        val future = service.getHttpResponse(
          ingest = ingest,
          callbackUri = new URI(
            s"http://$callbackHost:$callbackPort/callback/${ingest.id}")
        )

        whenReady(future) { result =>
          result.isSuccess shouldBe true
          result.get.status shouldBe StatusCodes.NotFound
        }
      }
    }
  }

  it("returns a failed future if the HTTP request fails") {
    withActorSystem { implicit actorSystem =>
      withCallbackUrlService { service =>
        val ingest = createIngest

        val future = service.getHttpResponse(
          ingest = ingest,
          callbackUri = new URI(s"http://nope.nope/callback/${ingest.id}")
        )

        whenReady(future) { result =>
          result.isFailure shouldBe true
        }
      }
    }
  }

  // TODO: Add a test that it sends the correct POST payload.
}
