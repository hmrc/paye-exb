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

import play.api.Logging
import play.api.libs.json.{JsError, JsValue, Json, Reads}
import play.api.mvc.{Action, AnyContent, ControllerComponents, Result}
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.payeexb.controllers.actions.IdentifierAction
import uk.gov.hmrc.payeexb.models.auth.IdentifierRequest
import uk.gov.hmrc.payeexb.models.clientlist.*
import uk.gov.hmrc.payeexb.service.ClientListService
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton()
class ClientListController @Inject()(
                                      cc: ControllerComponents,
                                      service: ClientListService,
                                      auth: IdentifierAction
                                    )(implicit ec: ExecutionContext) extends BackendController(cc) with Logging {

  def clientListStatus: Action[JsValue] = auth.async(parse.json) { implicit request =>
    withBody[ClientListStatusRequest] { body =>
      if (body.credentialId != request.credentialId) {
        logger.warn("[ClientListController][clientListStatus] credential ID does not match the signed-in agent")
        Future.successful(Forbidden(Json.obj("message" -> "Credential ID does not match the signed-in agent")))
      } else {
        handle("clientListStatus")(service.getClientListStatus(body).map(response => Ok(Json.toJson(response))))
      }
    }
  }

  def allClients: Action[JsValue] = auth.async(parse.json) { implicit request =>
    withBody[GetAllClientsRequest] { body =>
      if (body.credentialId != request.credentialId || body.irAgentId != request.agentReference) {
        logger.warn("[ClientListController][allClients] credential ID or agent reference does not match the signed-in agent")
        Future.successful(Forbidden(Json.obj("message" -> "Request does not match the signed-in agent")))
      } else {
        handle("allClients")(service.getAllClients(body).map(response => Ok(Json.toJson(response))))
      }
    }
  }

  def startClientListDownload(serviceName: String, credentialId: String): Action[AnyContent] = auth.async { implicit request =>
    if (credentialId != request.credentialId) {
      logger.warn("[ClientListController][startClientListDownload] credential ID does not match the signed-in agent")
      Future.successful(Forbidden(Json.obj("message" -> "Credential ID does not match the signed-in agent")))
    } else {
      handle("startClientListDownload") {
        service.startClientListDownload(serviceName, credentialId).map { response =>
          if (response.status == OK) {
            Ok(response.body)
          } else {
            logger.warn(s"[ClientListController][startClientListDownload] client exchange returned status ${response.status}")
            Status(response.status)(response.body)
          }
        }
      }
    }
  }

  private def withBody[B: Reads](f: B => Future[Result])(implicit request: IdentifierRequest[JsValue]): Future[Result] =
    request.body
      .validate[B]
      .fold(
        errs => Future.successful(BadRequest(Json.obj("message" -> "Invalid payload", "errors" -> JsError.toJson(errs)))),
        f
      )

  private def handle(method: String)(result: Future[Result]): Future[Result] =
    result.recover {
      case u: UpstreamErrorResponse =>
        Status(u.statusCode)(Json.obj("message" -> u.message))
      case NonFatal(t) =>
        logger.error(s"[ClientListController][$method] failed", t)
        InternalServerError(Json.obj("message" -> "Unexpected error"))
    }
}
