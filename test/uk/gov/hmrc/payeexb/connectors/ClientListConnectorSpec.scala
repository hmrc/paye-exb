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

import com.github.tomakehurst.wiremock.client.WireMock.*
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers.*
import uk.gov.hmrc.http.test.WireMockSupport
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}
import uk.gov.hmrc.payeexb.models.clientlist.*

class ClientListConnectorSpec extends AnyFreeSpec with Matchers with ScalaFutures with IntegrationPatience with WireMockSupport {

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  private def app(stubEnabled: Boolean): Application =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.paye-expenses-and-benefits-stub.port" -> wireMockPort,
        "microservice.services.paye-exb-client-list.port"            -> wireMockPort,
        "features.stub-paye-exb-enabled"                             -> stubEnabled,
        "http-verbs.retries.enabled"                                 -> false
      )
      .build()

  private lazy val connectorWithStub: ClientListConnector = app(stubEnabled = true).injector.instanceOf[ClientListConnector]
  private lazy val connectorWithReal: ClientListConnector = app(stubEnabled = false).injector.instanceOf[ClientListConnector]

  private val statusRequest = ClientListStatusRequest("cred-id", "PAYE", 14400)
  private val clientsRequest = GetAllClientsRequest("AB1234", "cred-id", 0, 30, 0, "ASC")

  private val clientsJson =
    """{
      |  "totalClients": 1,
      |  "clients": [{
      |    "instanceId": 1, "employerName1": "Hedgehog Woodshop", "districtNumber": 754,
      |    "employerRef": "KG12677", "aoReference": "754PA00012345", "previousYearFiled": true
      |  }],
      |  "clientNameChars": ["H"]
      |}""".stripMargin

  "ClientListConnector.getClientListStatus" - {

    "must call the stub when stub-paye-exb-enabled is true" in {
      wireMockServer.stubFor(
        post(urlEqualTo("/paye-expenses-and-benefits-stub/client-list/status"))
          .withRequestBody(equalToJson("""{ "credentialId": "cred-id", "service": "PAYE", "gracePeriod": 14400 }"""))
          .willReturn(okJson("""{ "status": 1 }"""))
      )

      connectorWithStub.stubEnabled mustBe true
      connectorWithStub.getClientListStatus(statusRequest).futureValue mustBe ClientListStatusResponse(1)
    }

    "must call the client list service when stub-paye-exb-enabled is false" in {
      wireMockServer.stubFor(
        post(urlEqualTo("/paye-exb-client-list/client-list/status")).willReturn(okJson("""{ "status": 0 }"""))
      )

      connectorWithReal.stubEnabled mustBe false
      connectorWithReal.getClientListStatus(statusRequest).futureValue mustBe ClientListStatusResponse(0)
    }

    "must fail with an UpstreamErrorResponse when the downstream returns a 500" in {
      wireMockServer.stubFor(
        post(urlEqualTo("/paye-expenses-and-benefits-stub/client-list/status")).willReturn(aResponse().withStatus(INTERNAL_SERVER_ERROR))
      )

      val error = connectorWithStub.getClientListStatus(statusRequest).failed.futureValue

      error mustBe an[UpstreamErrorResponse]
      error.asInstanceOf[UpstreamErrorResponse].statusCode mustBe INTERNAL_SERVER_ERROR
    }
  }

  "ClientListConnector.getAllClients" - {

    "must return the clients from the stub" in {
      wireMockServer.stubFor(
        post(urlEqualTo("/paye-expenses-and-benefits-stub/clients/all"))
          .withRequestBody(equalToJson("""{ "irAgentId": "AB1234", "credentialId": "cred-id", "start": 0, "count": 30, "sort": 0, "order": "ASC" }"""))
          .willReturn(okJson(clientsJson))
      )

      val result = connectorWithStub.getAllClients(clientsRequest).futureValue

      result.totalClients mustBe 1
      result.clients.head.employerName1 mustBe "Hedgehog Woodshop"
      result.clientNameChars mustBe Seq("H")
    }

    "must fail when the downstream returns invalid JSON" in {
      wireMockServer.stubFor(post(urlEqualTo("/paye-expenses-and-benefits-stub/clients/all")).willReturn(okJson("""{ "unexpected": true }""")))

      connectorWithStub.getAllClients(clientsRequest).failed.futureValue.getMessage must include("totalClients")
    }
  }
}
