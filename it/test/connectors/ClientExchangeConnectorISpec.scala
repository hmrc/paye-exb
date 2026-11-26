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
import com.github.tomakehurst.wiremock.http.Fault
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.payeexb.utils.WireMockHelper

class ClientExchangeConnectorISpec
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
    "microservice.services.client-exchange-proxy.protocol"           -> "http",
    "microservice.services.client-exchange-proxy.host"               -> "localhost",
    "microservice.services.client-exchange-proxy.port"               -> server.port(),
    "features.stub-paye-exb-enabled"                                 -> stubEnabled,
    "http-verbs.retries.enabled"                                     -> false
  )

  override def fakeApplication(): Application =
    new GuiceApplicationBuilder().configure(appConfig(stubEnabled = true)).build()

  private lazy val connector = app.injector.instanceOf[ClientExchangeConnector]

  private val stubUrl = "/paye-expenses-and-benefits-stub/clientlistexchange/PAYE/cred-id-1234/agent/clientlist"
  private val realUrl = "/client-exchange-proxy/PAYE/cred-id-1234/agent/clientlist"

  private val waitTimeXml =
    """<gwe:AsynchronousProcessWaitTime browserInterval="8000" xmlns:gwe="gwe"><BusinessServiceInterval>1000</BusinessServiceInterval></gwe:AsynchronousProcessWaitTime>"""

  private def stubExchange(url: String, status: Int, body: String = ""): Unit =
    server.stubFor(
      get(urlPathEqualTo(url))
        .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/xml").withBody(body))
    )

  "ClientExchangeConnector Integration Tests" - {

    "startClientListDownload" - {

      "must return the wait time XML from the stub" in {
        stubExchange(stubUrl, 200, waitTimeXml)

        val result = connector.startClientListDownload("PAYE", "cred-id-1234").futureValue

        result.status mustBe 200
        result.body mustBe waitTimeXml
      }

      "must make a single GET request to the stub" in {
        stubExchange(stubUrl, 200, waitTimeXml)

        connector.startClientListDownload("PAYE", "cred-id-1234").futureValue

        server.verify(1, getRequestedFor(urlPathEqualTo(stubUrl)))
        server.verify(0, postRequestedFor(anyUrl()))
      }

      "must call client-exchange-proxy instead of the stub when stub-paye-exb-enabled is false" in {
        stubExchange(realUrl, 200, waitTimeXml)

        val realApp = new GuiceApplicationBuilder().configure(appConfig(stubEnabled = false)).build()

        try {
          val realConnector = realApp.injector.instanceOf[ClientExchangeConnector]

          realConnector.stubEnabled mustBe false
          realConnector.startClientListDownload("PAYE", "cred-id-1234").futureValue.status mustBe 200

          server.verify(1, getRequestedFor(urlPathEqualTo(realUrl)))
          server.verify(0, getRequestedFor(urlPathEqualTo(stubUrl)))
        } finally {
          realApp.stop().futureValue
        }
      }

      Seq(400, 401, 403, 404, 500, 502, 503).foreach { status =>
        s"must return the response when the stub returns $status" in {
          stubExchange(stubUrl, status, "Error")

          val result = connector.startClientListDownload("PAYE", "cred-id-1234").futureValue

          result.status mustBe status
          result.body mustBe "Error"
        }
      }

      "must fail when the stub cannot be reached" in {
        server.stubFor(get(urlPathEqualTo(stubUrl)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))

        connector.startClientListDownload("PAYE", "cred-id-1234").failed.futureValue mustBe a[Throwable]
      }
    }
  }
}
