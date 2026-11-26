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

import play.api.Logging
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, StringContextOps}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class ClientExchangeConnector @Inject()(http: HttpClientV2, config: ServicesConfig)(implicit ec: ExecutionContext) extends Logging {

  private val stubPath: String     = config.baseUrl("paye-expenses-and-benefits-stub") + "/paye-expenses-and-benefits-stub/clientlistexchange"
  private val exchangePath: String = config.baseUrl("client-exchange-proxy") + "/client-exchange-proxy"

  val stubEnabled: Boolean = config.getBoolean("features.stub-paye-exb-enabled")

  private val basePath: String = if (stubEnabled) stubPath else exchangePath

  def startClientListDownload(service: String, credentialId: String)(implicit hc: HeaderCarrier): Future[HttpResponse] =
    http
      .get(url"$basePath/$service/$credentialId/agent/clientlist")
      .execute[HttpResponse]
      .recoverWith {
        case NonFatal(e) =>
          logger.error(s"[ClientExchangeConnector][startClientListDownload] failed: ${e.getMessage}")
          Future.failed(e)
      }
}
