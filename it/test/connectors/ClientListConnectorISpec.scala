/*
 * Copyright 2026 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.payeexb.connectors

import com.fasterxml.jackson.core.JsonParseException
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.http.Fault
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import uk.gov.hmrc.http.{HeaderCarrier, JsValidationException, UpstreamErrorResponse}
import uk.gov.hmrc.payeexb.models.clientlist.*
import uk.gov.hmrc.payeexb.utils.WireMockHelper

class ClientListConnectorISpec
  extends AnyFreeSpec
    with Matchers
    with ScalaFutures
    with IntegrationPatience
    with GuiceOneAppPerSuite
    with WireMockHelper {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  private def appConfig(stubEnabled: Boolean): Map[String, Any] = Map(
    "microservice.services.paye-expenses-and-benefits-stub.protocol" -> "http",
    "microservice.services.paye-expenses-and-benefits-stub.host"     -> "localhost",
    "microservice.services.paye-expenses-and-benefits-stub.port"     -> server.port(),
    "microservice.services.paye-exb-client-list.protocol"            -> "http",
    "microservice.services.paye-exb-client-list.host"                -> "localhost",
    "microservice.services.paye-exb-client-list.port"                -> server.port(),
    "features.stub-paye-exb-enabled"                                 -> stubEnabled,
    "http-verbs.retries.enabled"                                     -> false
  )

  override def fakeApplication(): Application =
    new GuiceApplicationBuilder().configure(appConfig(stubEnabled = true)).build()

  private lazy val connector = app.injector.instanceOf[ClientListConnector]

  private val stubStatusUrl   = "/paye-expenses-and-benefits-stub/client-list/status"
  private val stubClientsUrl  = "/paye-expenses-and-benefits-stub/clients/all"
  private val realStatusUrl   = "/paye-exb-client-list/client-list/status"
  private val realClientsUrl  = "/paye-exb-client-list/clients/all"

  private val statusRequest  = ClientListStatusRequest("cred-id-1234", "PAYE", 14400)
  private val clientsRequest = GetAllClientsRequest("AB1234", "cred-id-1234", 0, 30, 0, "ASC")

  private val client = PayeClient(1L, "Hedgehog Woodshop", Some("Trading"), 754, "KG12677", "754PA00012345", Some("REF1"), true)

  private val clientsResponse = GetAllClientsResponse(1, Seq(client), Seq("H"))

  private def stubPost(url: String, status: Int, body: String): Unit =
    server.stubFor(
      post(urlPathEqualTo(url))
        .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body))
    )

  private def withRealConnector(test: ClientListConnector => Unit): Unit = {
    val realApp = new GuiceApplicationBuilder().configure(appConfig(stubEnabled = false)).build()
    try test(realApp.injector.instanceOf[ClientListConnector])
    finally realApp.stop().futureValue
  }

  "ClientListConnector Integration Tests" - {

    "getClientListStatus" - {

      "must return each status returned by the stub" in {
        Seq(-1, 0, 1, 2).foreach { status =>
          stubPost(stubStatusUrl, 200, Json.obj("status" -> status).toString)
          connector.getClientListStatus(statusRequest).futureValue mustBe ClientListStatusResponse(status)
        }
      }

      "must send the request to the stub as JSON" in {
        stubPost(stubStatusUrl, 200, Json.obj("status" -> 1).toString)

        connector.getClientListStatus(statusRequest).futureValue

        server.verify(
          1,
          postRequestedFor(urlPathEqualTo(stubStatusUrl))
            .withHeader("Content-Type", containing("application/json"))
            .withRequestBody(equalToJson(Json.toJson(statusRequest).toString))
        )
      }

      "must call paye-exb-client-list instead of the stub when stub-paye-exb-enabled is false" in {
        stubPost(realStatusUrl, 200, Json.obj("status" -> 0).toString)

        withRealConnector { realConnector =>
          realConnector.stubEnabled mustBe false
          realConnector.getClientListStatus(statusRequest).futureValue mustBe ClientListStatusResponse(0)

          server.verify(1, postRequestedFor(urlPathEqualTo(realStatusUrl)))
          server.verify(0, postRequestedFor(urlPathEqualTo(stubStatusUrl)))
        }
      }

      Seq(400, 401, 403, 404, 500, 502, 503).foreach { status =>
        s"must fail with an UpstreamErrorResponse when the stub returns $status" in {
          stubPost(stubStatusUrl, status, "")

          val result = connector.getClientListStatus(statusRequest).failed.futureValue

          result mustBe an[UpstreamErrorResponse]
          result.asInstanceOf[UpstreamErrorResponse].statusCode mustBe status
        }
      }

      "must fail when the stub returns JSON with missing fields" in {
        stubPost(stubStatusUrl, 200, Json.obj().toString)

        connector.getClientListStatus(statusRequest).failed.futureValue mustBe a[JsValidationException]
      }

      "must fail when the stub returns malformed JSON" in {
        stubPost(stubStatusUrl, 200, "{invalid json}")

        connector.getClientListStatus(statusRequest).failed.futureValue mustBe a[JsonParseException]
      }

      "must fail when the stub cannot be reached" in {
        server.stubFor(post(urlPathEqualTo(stubStatusUrl)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))

        connector.getClientListStatus(statusRequest).failed.futureValue mustBe a[Throwable]
      }
    }

    "getAllClients" - {

      "must return the clients returned by the stub" in {
        stubPost(stubClientsUrl, 200, Json.toJson(clientsResponse).toString)

        connector.getAllClients(clientsRequest).futureValue mustBe clientsResponse
      }

      "must return an empty response when the agent has no clients" in {
        stubPost(stubClientsUrl, 200, Json.toJson(GetAllClientsResponse(0, Nil, Nil)).toString)

        connector.getAllClients(clientsRequest).futureValue mustBe GetAllClientsResponse(0, Nil, Nil)
      }

      "must send the request to the stub as JSON" in {
        stubPost(stubClientsUrl, 200, Json.toJson(clientsResponse).toString)

        connector.getAllClients(clientsRequest).futureValue

        server.verify(
          1,
          postRequestedFor(urlPathEqualTo(stubClientsUrl))
            .withHeader("Content-Type", containing("application/json"))
            .withRequestBody(equalToJson(Json.toJson(clientsRequest).toString))
        )
      }

      "must call paye-exb-client-list instead of the stub when stub-paye-exb-enabled is false" in {
        stubPost(realClientsUrl, 200, Json.toJson(clientsResponse).toString)

        withRealConnector { realConnector =>
          realConnector.getAllClients(clientsRequest).futureValue mustBe clientsResponse

          server.verify(1, postRequestedFor(urlPathEqualTo(realClientsUrl)))
          server.verify(0, postRequestedFor(urlPathEqualTo(stubClientsUrl)))
        }
      }

      Seq(400, 401, 403, 404, 500, 502, 503).foreach { status =>
        s"must fail with an UpstreamErrorResponse when the stub returns $status" in {
          stubPost(stubClientsUrl, status, "")

          val result = connector.getAllClients(clientsRequest).failed.futureValue

          result mustBe an[UpstreamErrorResponse]
          result.asInstanceOf[UpstreamErrorResponse].statusCode mustBe status
        }
      }

      "must fail when the stub returns JSON with missing fields" in {
        stubPost(stubClientsUrl, 200, Json.obj("totalClients" -> 1).toString)

        connector.getAllClients(clientsRequest).failed.futureValue mustBe a[JsValidationException]
      }

      "must fail when the stub returns malformed JSON" in {
        stubPost(stubClientsUrl, 200, "{invalid json}")

        connector.getAllClients(clientsRequest).failed.futureValue mustBe a[JsonParseException]
      }

      "must fail when the stub cannot be reached" in {
        server.stubFor(post(urlPathEqualTo(stubClientsUrl)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))

        connector.getAllClients(clientsRequest).failed.futureValue mustBe a[Throwable]
      }
    }
  }
}
