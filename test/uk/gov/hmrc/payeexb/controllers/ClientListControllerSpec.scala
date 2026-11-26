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

package uk.gov.hmrc.payeexb.controllers

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{never, reset, verify, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.Result
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, UpstreamErrorResponse}
import uk.gov.hmrc.payeexb.controllers.actions.FakeIdentifierAction
import uk.gov.hmrc.payeexb.models.clientlist.*
import uk.gov.hmrc.payeexb.service.ClientListService

import scala.concurrent.{ExecutionContext, Future}

class ClientListControllerSpec extends AnyFreeSpec with Matchers with MockitoSugar with BeforeAndAfterEach {

  private implicit val system: ActorSystem       = ActorSystem("ClientListControllerSpec")
  private implicit val materializer: Materializer = Materializer(system)
  private implicit val ec: ExecutionContext      = ExecutionContext.global

  private val cc      = stubControllerComponents()
  private val service = mock[ClientListService]

  private val controller = new ClientListController(cc, service, new FakeIdentifierAction(cc.parsers))

  override def beforeEach(): Unit = {
    super.beforeEach()
    reset(service)
  }

  private val credentialId   = FakeIdentifierAction.credentialId
  private val agentReference = FakeIdentifierAction.agentReference

  private def postJson(action: play.api.mvc.Action[JsValue], body: JsValue): Future[Result] =
    action(FakeRequest(POST, "/").withHeaders(CONTENT_TYPE -> JSON).withBody(body))

  private val statusBody  = Json.obj("credentialId" -> credentialId, "service" -> "PAYE", "gracePeriod" -> 14400)
  private val clientsBody = Json.obj("irAgentId" -> agentReference, "credentialId" -> credentialId, "start" -> 0, "count" -> 30, "sort" -> 0, "order" -> "ASC")

  "ClientListController.clientListStatus" - {

    "must return 200 and the status" in {
      when(service.getClientListStatus(any[ClientListStatusRequest])(any[HeaderCarrier])).thenReturn(Future.successful(ClientListStatusResponse(0)))

      val result = postJson(controller.clientListStatus, statusBody)

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.obj("status" -> 0)
      verify(service).getClientListStatus(eqTo(ClientListStatusRequest(credentialId, "PAYE", 14400)))(any[HeaderCarrier])
    }

    "must return 400 when the payload is invalid" in {
      val result = postJson(controller.clientListStatus, Json.obj("somethingElse" -> "x"))

      status(result) mustBe BAD_REQUEST
      (contentAsJson(result) \ "message").as[String] mustBe "Invalid payload"
      verify(service, never()).getClientListStatus(any[ClientListStatusRequest])(any[HeaderCarrier])
    }

    "must return 403 when the credential ID is not the signed-in agent's" in {
      val result = postJson(controller.clientListStatus, statusBody ++ Json.obj("credentialId" -> "someone-else"))

      status(result) mustBe FORBIDDEN
      verify(service, never()).getClientListStatus(any[ClientListStatusRequest])(any[HeaderCarrier])
    }

    "must pass on the status of an upstream error" in {
      when(service.getClientListStatus(any[ClientListStatusRequest])(any[HeaderCarrier]))
        .thenReturn(Future.failed(UpstreamErrorResponse("boom", INTERNAL_SERVER_ERROR)))

      status(postJson(controller.clientListStatus, statusBody)) mustBe INTERNAL_SERVER_ERROR
    }

    "must return 500 for any other failure" in {
      when(service.getClientListStatus(any[ClientListStatusRequest])(any[HeaderCarrier]))
        .thenReturn(Future.failed(new RuntimeException("kaboom")))

      val result = postJson(controller.clientListStatus, statusBody)

      status(result) mustBe INTERNAL_SERVER_ERROR
      (contentAsJson(result) \ "message").as[String] mustBe "Unexpected error"
    }
  }

  "ClientListController.allClients" - {

    "must return 200 and the clients" in {
      val response = GetAllClientsResponse(1, Seq(PayeClient(1L, "Hedgehog Woodshop", None, 754, "KG12677", "754PA00012345", Some("REF1"), previousYearFiled = true)), Seq("H"))
      when(service.getAllClients(any[GetAllClientsRequest])(any[HeaderCarrier])).thenReturn(Future.successful(response))

      val result = postJson(controller.allClients, clientsBody)

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.toJson(response)
    }

    "must return 403 when the agent reference is not the signed-in agent's" in {
      val result = postJson(controller.allClients, clientsBody ++ Json.obj("irAgentId" -> "ZZ9999"))

      status(result) mustBe FORBIDDEN
      verify(service, never()).getAllClients(any[GetAllClientsRequest])(any[HeaderCarrier])
    }

    "must return 403 when the credential ID is not the signed-in agent's" in {
      status(postJson(controller.allClients, clientsBody ++ Json.obj("credentialId" -> "someone-else"))) mustBe FORBIDDEN
    }

    "must return 400 when the payload is invalid" in {
      status(postJson(controller.allClients, Json.obj("irAgentId" -> agentReference))) mustBe BAD_REQUEST
    }

    "must pass on the status of an upstream error" in {
      when(service.getAllClients(any[GetAllClientsRequest])(any[HeaderCarrier]))
        .thenReturn(Future.failed(UpstreamErrorResponse("unavailable", SERVICE_UNAVAILABLE)))

      status(postJson(controller.allClients, clientsBody)) mustBe SERVICE_UNAVAILABLE
    }
  }

  "ClientListController.startClientListDownload" - {

    "must return 200 when the client exchange returns 200" in {
      when(service.startClientListDownload(eqTo("PAYE"), eqTo(credentialId))(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(OK, "<waitTime>60</waitTime>")))

      val result = controller.startClientListDownload("PAYE", credentialId)(FakeRequest())

      status(result) mustBe OK
      contentAsString(result) mustBe "<waitTime>60</waitTime>"
    }

    "must pass on the status when the client exchange returns an error" in {
      when(service.startClientListDownload(eqTo("PAYE"), eqTo(credentialId))(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(BAD_REQUEST, "bad")))

      status(controller.startClientListDownload("PAYE", credentialId)(FakeRequest())) mustBe BAD_REQUEST
    }

    "must return 403 when the credential ID is not the signed-in agent's" in {
      status(controller.startClientListDownload("PAYE", "someone-else")(FakeRequest())) mustBe FORBIDDEN
      verify(service, never()).startClientListDownload(any[String], any[String])(any[HeaderCarrier])
    }

    "must return 500 when the call fails" in {
      when(service.startClientListDownload(eqTo("PAYE"), eqTo(credentialId))(any[HeaderCarrier]))
        .thenReturn(Future.failed(new RuntimeException("kaboom")))

      status(controller.startClientListDownload("PAYE", credentialId)(FakeRequest())) mustBe INTERNAL_SERVER_ERROR
    }
  }
}
