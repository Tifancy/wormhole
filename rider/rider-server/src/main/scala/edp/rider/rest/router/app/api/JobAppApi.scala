/*-
 * <<
 * wormhole
 * ==
 * Copyright (C) 2016 - 2017 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */


package edp.rider.rest.router.app.api

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route
import edp.rider.common.{AppInfo, RiderLogger}
import edp.rider.rest.persistence.dal.{JobDal, ProjectDal}
import edp.rider.rest.persistence.entities._
import edp.rider.rest.router.JsonProtocol._
import edp.rider.rest.router.{ResponseJson, SessionClass}
import edp.rider.rest.util.AppUtils._
import edp.rider.rest.util.AuthorizationProvider
import edp.rider.rest.util.CommonUtils._
import edp.rider.rest.util.JobUtils._
import edp.rider.rest.util.ResponseUtils._

import scala.concurrent.Await

class JobAppApi(jobDal: JobDal, projectDal: ProjectDal) extends BaseAppApiImpl(jobDal) with RiderLogger {

  def postRoute(route: String): Route = path(route / LongNumber / "jobs") {
    projectId =>
      post {
        entity(as[AppJob]) {
          appJob =>
            authenticateOAuth2Async[SessionClass]("rider", AuthorizationProvider.authorize) {
              session =>
                if (session.roleType != "app") {
                  riderLogger.warn(s"${session.userId} has no permission to access it.")
                  complete(Forbidden, getHeader(403, null))
                } else {
                  try {
                    prepare(Some(appJob), None, session, projectId) match {
                      case Right(tuple) =>
                        val job = tuple._1.get
                        riderLogger.info(s"job start: $job")
                        try {
                          startJob(job)
                          riderLogger.info(s"user ${session.userId} start job ${job.id} success.")
                          complete(OK, ResponseJson[AppJobResponse](getHeader(200, null), AppJobResponse(job.id, job.status)))
                        } catch {
                          case ex: Exception =>
                            riderLogger.error(s"user ${session.userId} start job ${job.id} failed", ex)
                            jobDal.updateJobStatus(job.id, AppInfo(null, "failed", job.startedTime.get, currentSec))
                            complete(UnavailableForLegalReasons, getHeader(451, null))
                        }
                      case Left(response) =>
                        complete(Forbidden, response)
                    }
                  } catch {
                    case ex: Exception =>
                      riderLogger.error(s"user ${session.userId} start job $appJob failed", ex)
                      complete(UnavailableForLegalReasons, getHeader(451, null))
                  }
                }
            }
        }
      }
  }

  def stopRoute(route: String): Route = path(route / LongNumber / "jobs" / LongNumber / "actions" / "stop") {
    (projectId, jobId) =>
      put {
        authenticateOAuth2Async[SessionClass]("rider", AuthorizationProvider.authorize) {
          session =>
            if (session.roleType != "app") {
              riderLogger.warn(s"${session.userId} has no permission to access it.")
              complete(Forbidden, getHeader(403, null))
            } else {
              try {
                val project = Await.result(projectDal.findById(projectId), minTimeOut)
                if (project.isEmpty) {
                  riderLogger.error(s"user ${session.userId} request to stop project $projectId, job $jobId, but the project $projectId doesn't exist.")
                  complete(Forbidden, getHeader(403, s"project $projectId doesn't exist", null))
                }
                val job = Await.result(jobDal.findById(projectId), minTimeOut)
                if (job.isEmpty) {
                  riderLogger.error(s"user ${session.userId} request to stop project $projectId, job $jobId, but the job $jobId doesn't exist.")
                  complete(Forbidden, getHeader(403, s"job $jobId doesn't exist", null))
                }
                val status = killJob(jobId)
                riderLogger.info(s"user ${session.userId} stop job $jobId success.")
                complete(OK, ResponseJson[AppJobResponse](getHeader(200, null), AppJobResponse(jobId, status)))
              } catch {
                case ex: Exception =>
                  riderLogger.error(s"user ${session.userId} stop job $jobId failed", ex)
                  complete(UnavailableForLegalReasons, getHeader(451, null))
              }
            }
        }
      }
  }
}
