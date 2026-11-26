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
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers.*
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.WireMockSupport

class ClientExchangeConnectorSpec extends AnyFreeSpec with Matchers with ScalaFutures with IntegrationPatience with WireMockSupport {

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  private def app(stubEnabled: Boolean): Application =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.paye-expenses-and-benefits-stub.port" -> wireMockPort,
        "microservice.services.client-exchange-proxy.port"           -> wireMockPort,
        "features.stub-paye-exb-enabled"                             -> stubEnabled,
        "http-verbs.retries.enabled"                                 -> false
      )
      .build()

  private lazy val connectorWithStub: ClientExchangeConnector = app(stubEnabled = true).injector.instanceOf[ClientExchangeConnector]
  private lazy val connectorWithReal: ClientExchangeConnector = app(stubEnabled = false).injector.instanceOf[ClientExchangeConnector]

  private val stubUrl = "/paye-expenses-and-benefits-stub/clientlistexchange/PAYE/cred-id/agent/clientlist"
  private val realUrl = "/client-exchange-proxy/PAYE/cred-id/agent/clientlist"

  "ClientExchangeConnector.startClientListDownload" - {

    "must call the stub when stub-paye-exb-enabled is true" in {
      wireMockServer.stubFor(get(urlEqualTo(stubUrl)).willReturn(ok("<waitTime>60</waitTime>")))

      val response = connectorWithStub.startClientListDownload("PAYE", "cred-id").futureValue

      response.status mustBe OK
      response.body mustBe "<waitTime>60</waitTime>"
    }

    "must call the client exchange proxy when stub-paye-exb-enabled is false" in {
      wireMockServer.stubFor(get(urlEqualTo(realUrl)).willReturn(ok()))

      connectorWithReal.stubEnabled mustBe false
      connectorWithReal.startClientListDownload("PAYE", "cred-id").futureValue.status mustBe OK
    }

    "must return the response when the downstream returns an error status" in {
      wireMockServer.stubFor(get(urlEqualTo(stubUrl)).willReturn(aResponse().withStatus(BAD_REQUEST)))

      connectorWithStub.startClientListDownload("PAYE", "cred-id").futureValue.status mustBe BAD_REQUEST
    }

    "must fail when the connection fails" in {
      wireMockServer.stubFor(get(urlEqualTo(stubUrl)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))

      connectorWithStub.startClientListDownload("PAYE", "cred-id").failed.futureValue mustBe a[Throwable]
    }
  }
}
