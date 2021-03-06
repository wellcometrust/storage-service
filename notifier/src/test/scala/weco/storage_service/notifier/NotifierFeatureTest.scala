package weco.storage_service.notifier

import java.net.URI

import com.github.tomakehurst.wiremock.client.WireMock._
import org.apache.http.HttpStatus
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import org.scalatest.Inside
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import weco.akka.fixtures.Akka
import weco.json.JsonUtil._
import weco.storage_service.bagit.models.BagVersion
import weco.storage_service.generators.IngestGenerators
import weco.storage_service.ingests.models._
import weco.storage_service.notifier.fixtures.{
  LocalWireMockFixture,
  NotifierFixtures
}
import weco.fixtures.TimeAssertions

class NotifierFeatureTest
    extends AnyFunSpec
    with Matchers
    with ScalaFutures
    with Akka
    with IntegrationPatience
    with LocalWireMockFixture
    with NotifierFixtures
    with Inside
    with IngestGenerators
    with TimeAssertions
    with Eventually {

  describe("Making callbacks") {
    it("makes a POST request when it receives an Ingest with a callback") {
      withLocalWireMockClient { wireMock =>
        withNotifier {
          case (queue, _) =>
            val ingestId = createIngestID

            val callbackUri =
              new URI(s"http://$callbackHost:$callbackPort/callback/$ingestId")

            val ingest = createIngestWith(
              id = ingestId,
              callback = Some(createCallbackWith(uri = callbackUri)),
              events = createIngestEvents(count = 2),
              version = None
            )

            sendNotificationToSQS(
              queue,
              CallbackNotification(ingestId, callbackUri, ingest)
            )

            val ingestLocation =
              ingest.sourceLocation.asInstanceOf[S3SourceLocation]

            val expectedJson =
              s"""
                 |{
                 |  "id": "${ingest.id.toString}",
                 |  "type": "Ingest",
                 |  "ingestType": {
                 |    "id": "${ingest.ingestType.id}",
                 |    "type": "IngestType"
                 |  },
                 |  "space": {
                 |    "id": "${ingest.space.underlying}",
                 |    "type": "Space"
                 |  },
                 |  "bag": {
                 |    "type": "Bag",
                 |    "info": {
                 |      "type": "BagInfo",
                 |      "externalIdentifier": "${ingest.externalIdentifier.underlying}"
                 |    }
                 |  },
                 |  "status": {
                 |    "id": "${ingest.status.toString}",
                 |    "type": "Status"
                 |  },
                 |  "sourceLocation": {
                 |    "type": "Location",
                 |    "provider": {
                 |      "type": "Provider",
                 |      "id": "amazon-s3"
                 |    },
                 |    "bucket": "${ingestLocation.location.bucket}",
                 |    "path": "${ingestLocation.location.key}"
                 |  },
                 |  "callback": {
                 |    "type": "Callback",
                 |    "url": "${ingest.callback.get.uri}",
                 |    "status": {
                 |      "id": "${ingest.callback.get.status.toString}",
                 |      "type": "Status"
                 |    }
                 |  },
                 |  "createdDate": "${ingest.createdDate}",
                 |  "lastModifiedDate": "${ingest.lastModifiedDate.get}",
                 |  "events": [
                 |    {
                 |      "type": "IngestEvent",
                 |      "createdDate": "${ingest.events(0).createdDate}",
                 |      "description": "${ingest.events(0).description}"
                 |    },
                 |    {
                 |      "type": "IngestEvent",
                 |      "createdDate": "${ingest.events(1).createdDate}",
                 |      "description": "${ingest.events(1).description}"
                 |    }
                 |  ]
                 |}
                 """.stripMargin

            eventually {
              wireMock.verifyThat(
                postRequestedFor(urlPathEqualTo(callbackUri.getPath))
                  .withRequestBody(equalToJson(expectedJson))
              )
            }
        }
      }
    }
  }

  import org.scalatest.prop.TableDrivenPropertyChecks._

  val successfulStatuscodes =
    Table(
      "status code",
      HttpStatus.SC_OK,
      HttpStatus.SC_CREATED,
      HttpStatus.SC_ACCEPTED,
      HttpStatus.SC_NO_CONTENT
    )
  describe("Updating status") {
    it("sends an IngestUpdate when it receives a successful callback") {
      forAll(successfulStatuscodes) { statusResponse: Int =>
        withLocalWireMockClient { wireMock =>
          withNotifier {
            case (queue, messageSender) =>
              val ingestID = createIngestID

              val callbackPath = s"/callback/$ingestID"
              val callbackUri = new URI(
                s"http://$callbackHost:$callbackPort" + callbackPath
              )

              stubFor(
                post(urlEqualTo(callbackPath))
                  .willReturn(aResponse().withStatus(statusResponse))
              )

              val ingest = createIngestWith(
                id = ingestID,
                callback = Some(createCallbackWith(uri = callbackUri)),
                events = createIngestEvents(count = 2),
                version = Some(BagVersion(2))
              )

              sendNotificationToSQS(
                queue,
                CallbackNotification(ingestID, callbackUri, ingest)
              )

              val ingestLocation =
                ingest.sourceLocation.asInstanceOf[S3SourceLocation]

              val expectedJson =
                s"""
                   |{
                   |  "id": "${ingest.id.toString}",
                   |  "type": "Ingest",
                   |  "ingestType": {
                   |    "id": "${ingest.ingestType.id}",
                   |    "type": "IngestType"
                   |  },
                   |  "space": {
                   |    "id": "${ingest.space.underlying}",
                   |    "type": "Space"
                   |  },
                   |  "bag": {
                   |    "type": "Bag",
                   |    "info": {
                   |      "type": "BagInfo",
                   |      "version": "v2",
                   |      "externalIdentifier": "${ingest.externalIdentifier.underlying}"
                   |    }
                   |  },
                   |  "status": {
                   |    "id": "${ingest.status.toString}",
                   |    "type": "Status"
                   |  },
                   |  "sourceLocation": {
                   |    "type": "Location",
                   |    "provider": {
                   |      "type": "Provider",
                   |      "id": "amazon-s3"
                   |    },
                   |    "bucket": "${ingestLocation.location.bucket}",
                   |    "path": "${ingestLocation.location.key}"
                   |  },
                   |  "callback": {
                   |    "type": "Callback",
                   |    "url": "${ingest.callback.get.uri}",
                   |    "status": {
                   |      "id": "${ingest.callback.get.status.toString}",
                   |      "type": "Status"
                   |    }
                   |  },
                   |  "createdDate": "${ingest.createdDate}",
                   |  "lastModifiedDate": "${ingest.lastModifiedDate.get}",
                   |  "events": [
                   |    {
                   |      "type": "IngestEvent",
                   |      "createdDate": "${ingest.events(0).createdDate}",
                   |      "description": "${ingest.events(0).description}"
                   |    },
                   |    {
                   |      "type": "IngestEvent",
                   |      "createdDate": "${ingest.events(1).createdDate}",
                   |      "description": "${ingest.events(1).description}"
                   |    }
                   |  ]
                   |}
                 """.stripMargin

              eventually {
                wireMock.verifyThat(
                  1,
                  postRequestedFor(urlPathEqualTo(callbackUri.getPath))
                    .withRequestBody(equalToJson(expectedJson))
                )

                val updates = messageSender.getMessages[IngestUpdate]
                updates should have size 1
                val receivedUpdate = updates.head

                inside(receivedUpdate) {
                  case IngestCallbackStatusUpdate(
                      id,
                      callbackStatus,
                      List(ingestEvent)
                      ) =>
                    id shouldBe ingest.id
                    ingestEvent.description shouldBe "Callback fulfilled"
                    callbackStatus shouldBe Callback.Succeeded
                    assertRecent(ingestEvent.createdDate)
                }
              }
          }
        }
      }
    }

    it(
      "sends an IngestUpdate when it receives an Ingest with a callback it cannot fulfill"
    ) {
      withNotifier {
        case (queue, messageSender) =>
          val ingestId = createIngestID

          val callbackUri = new URI(
            s"http://$callbackHost:$callbackPort/callback/$ingestId"
          )

          val ingest = createIngestWith(
            id = ingestId,
            callback = Some(createCallbackWith(uri = callbackUri))
          )

          sendNotificationToSQS[CallbackNotification](
            queue,
            CallbackNotification(ingestId, callbackUri, ingest)
          )

          eventually {
            val updates = messageSender.getMessages[IngestUpdate]
            updates should have size 1
            val receivedUpdate = updates.head

            inside(receivedUpdate) {
              case IngestCallbackStatusUpdate(
                  id,
                  callbackStatus,
                  List(ingestEvent)
                  ) =>
                id shouldBe ingest.id
                ingestEvent.description shouldBe s"Callback failed for: ${ingest.id}, got 404 Not Found!"
                callbackStatus shouldBe Callback.Failed
                assertRecent(ingestEvent.createdDate)
            }
          }
      }
    }
  }
}
