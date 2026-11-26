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

package uk.gov.hmrc.payeexb.service

import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.payeexb.connectors.{ClientExchangeConnector, ClientListConnector}
import uk.gov.hmrc.payeexb.models.clientlist.*

import scala.concurrent.Future

class ClientListServiceSpec extends AnyFreeSpec with Matchers with MockitoSugar with ScalaFutures {

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  private val clientList     = mock[ClientListConnector]
  private val clientExchange = mock[ClientExchangeConnector]
  private val service        = new ClientListService(clientList, clientExchange)

  "ClientListService" - {

    "must return the client list status from the connector" in {
      val request = ClientListStatusRequest("cred-id", "PAYE", 14400)
      when(clientList.getClientListStatus(eqTo(request))(any[HeaderCarrier])).thenReturn(Future.successful(ClientListStatusResponse(1)))

      service.getClientListStatus(request).futureValue mustBe ClientListStatusResponse(1)
    }

    "must return all clients from the connector" in {
      val request  = GetAllClientsRequest("AB1234", "cred-id", 0, 30, 0, "ASC")
      val response = GetAllClientsResponse(0, Nil, Nil)
      when(clientList.getAllClients(eqTo(request))(any[HeaderCarrier])).thenReturn(Future.successful(response))

      service.getAllClients(request).futureValue mustBe response
    }

    "must start the client list download through the client exchange connector" in {
      when(clientExchange.startClientListDownload(eqTo("PAYE"), eqTo("cred-id"))(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(200, "")))

      service.startClientListDownload("PAYE", "cred-id").futureValue.status mustBe 200
    }
  }
}
