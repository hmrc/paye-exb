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

package uk.gov.hmrc.payeexb.controllers.actions

import com.google.inject.Inject
import play.api.Logging
import play.api.mvc.*
import play.api.mvc.Results.{Forbidden, Unauthorized}
import uk.gov.hmrc.auth.core.*
import uk.gov.hmrc.auth.core.retrieve.v2.Retrievals
import uk.gov.hmrc.auth.core.retrieve.{Credentials, ~}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.payeexb.models.auth.IdentifierRequest
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import scala.concurrent.{ExecutionContext, Future}

class AuthenticatedIdentifierAction @Inject()(
                                               override val authConnector: AuthConnector,
                                               val parser: BodyParsers.Default
                                             )(implicit val executionContext: ExecutionContext)
  extends IdentifierAction with AuthorisedFunctions with Logging {

  import AuthenticatedIdentifierAction.*

  override def invokeBlock[A](request: Request[A], block: IdentifierRequest[A] => Future[Result]): Future[Result] = {
    given hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)

    authorised(AffinityGroup.Agent)
      .retrieve(Retrievals.credentials and Retrievals.allEnrolments) {
        case Some(Credentials(credId, _)) ~ enrolments =>
          agentReference(enrolments) match {
            case Some(reference) =>
              block(IdentifierRequest(request, credId, reference))
            case None =>
              logger.warn(s"[AuthenticatedIdentifierAction][invokeBlock] - agent has no activated $AgentKey enrolment")
              Future.successful(Forbidden)
          }
        case _ =>
          logger.warn("[AuthenticatedIdentifierAction][invokeBlock] - agent is missing credentials")
          Future.successful(Forbidden)
      }
      .recover {
        case e: NoActiveSession =>
          logger.warn(s"[AuthenticatedIdentifierAction][invokeBlock] - no active session: ${e.getMessage}")
          Unauthorized
        case e: AuthorisationException =>
          logger.warn(s"[AuthenticatedIdentifierAction][invokeBlock] - not authorised: ${e.getMessage}")
          Forbidden
      }
  }
}

object AuthenticatedIdentifierAction {
  val AgentKey: String        = "IR-PAYE-AGENT"
  val AgentIdentifier: String = "IRAgentReference"

  def agentReference(enrolments: Enrolments): Option[String] =
    enrolments
      .getEnrolment(AgentKey)
      .filter(_.isActivated)
      .flatMap(_.getIdentifier(AgentIdentifier))
      .map(_.value)
      .filter(_.nonEmpty)
}
