/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.testingUtils

import java.util.UUID

import akka.actor.{Props, Kill, ActorSystem, ActorRef}
import akka.pattern.ask
import com.google.common.util.concurrent.MoreExecutors

import com.typesafe.config.ConfigFactory
import grizzled.slf4j.Logger
import org.apache.flink.api.common.JobExecutionResult

import org.apache.flink.configuration.{ConfigConstants, Configuration}
import org.apache.flink.runtime.client.JobClient
import org.apache.flink.runtime.clusterframework.FlinkResourceManager
import org.apache.flink.runtime.jobgraph.JobGraph
import org.apache.flink.runtime.clusterframework.types.ResourceID
import org.apache.flink.runtime.jobmanager.{MemoryArchivist, JobManager}
import org.apache.flink.runtime.testutils.TestingResourceManager
import org.apache.flink.runtime.util.LeaderRetrievalUtils
import org.apache.flink.runtime.{LogMessages, LeaderSessionMessageFilter, FlinkActor}
import org.apache.flink.runtime.akka.AkkaUtils
import org.apache.flink.runtime.instance.{AkkaActorGateway, ActorGateway}
import org.apache.flink.runtime.leaderretrieval.StandaloneLeaderRetrievalService
import org.apache.flink.runtime.messages.TaskManagerMessages.NotifyWhenRegisteredAtJobManager
import org.apache.flink.runtime.taskmanager.TaskManager

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.language.postfixOps

/**
 * Convenience functions to test actor based components.
 */
object TestingUtils {

  val testConfig = ConfigFactory.parseString(getDefaultTestingActorSystemConfigString)

  val TESTING_DURATION = 2 minute

  val DEFAULT_AKKA_ASK_TIMEOUT = "200 s"

  def getDefaultTestingActorSystemConfigString: String = {
    val logLevel = AkkaUtils.getLogLevel

    s"""akka.daemonic = on
      |akka.test.timefactor = 10
      |akka.loggers = ["akka.event.slf4j.Slf4jLogger"]
      |akka.loglevel = $logLevel
      |akka.stdout-loglevel = OFF
      |akka.jvm-exit-on-fatal-error = off
      |akka.log-config-on-start = off
    """.stripMargin
  }

  def getDefaultTestingActorSystemConfig = testConfig
  

  def startTestingCluster(numSlots: Int, numTMs: Int = 1,
                          timeout: String = DEFAULT_AKKA_ASK_TIMEOUT): TestingCluster = {
    val config = new Configuration()
    config.setInteger(ConfigConstants.TASK_MANAGER_NUM_TASK_SLOTS, numSlots)
    config.setInteger(ConfigConstants.LOCAL_NUMBER_TASK_MANAGER, numTMs)
    config.setString(ConfigConstants.AKKA_ASK_TIMEOUT, timeout)

    val cluster = new TestingCluster(config)

    cluster.start()

    cluster
  }

  /** Returns the global [[ExecutionContext]] which is a [[scala.concurrent.forkjoin.ForkJoinPool]]
    * with a default parallelism equal to the number of available cores.
    *
    * @return ExecutionContext.global
    */
  def defaultExecutionContext = ExecutionContext.global

  /** Returns an [[ExecutionContext]] which uses the current thread to execute the runnable.
    *
    * @return Direct [[ExecutionContext]] which executes runnables directly
    */
  def directExecutionContext = ExecutionContext.fromExecutor(MoreExecutors.directExecutor())

  /** @return A new [[QueuedActionExecutionContext]] */
  def queuedActionExecutionContext = {
    new QueuedActionExecutionContext(new ActionQueue())
  }

  /** [[ExecutionContext]] which queues [[Runnable]] up in an [[ActionQueue]] instead of
    * execution them. If the automatic execution mode is activated, then the [[Runnable]] are
    * executed.
    *
    * @param actionQueue
    */
  class QueuedActionExecutionContext private[testingUtils] (val actionQueue: ActionQueue)
    extends ExecutionContext {

    var automaticExecution = false

    def toggleAutomaticExecution() = {
      automaticExecution = !automaticExecution
    }

    override def execute(runnable: Runnable): Unit = {
      if(automaticExecution){
        runnable.run()
      }else {
        actionQueue.queueAction(runnable)
      }
    }

    override def reportFailure(t: Throwable): Unit = {
      t.printStackTrace()
    }
  }

  /** Queue which stores [[Runnable]] */
  class ActionQueue {
    private val runnables = scala.collection.mutable.Queue[Runnable]()

    def triggerNextAction {
      val r = runnables.dequeue
      r.run()
    }

    def popNextAction: Runnable = {
      runnables.dequeue()
    }

    def queueAction(r: Runnable) {
      runnables.enqueue(r)
    }

    def isEmpty: Boolean = {
      runnables.isEmpty
    }
  }

  def createTaskManager(
    actorSystem: ActorSystem,
    jobManager: ActorRef,
    configuration: Configuration,
    useLocalCommunication: Boolean,
    waitForRegistration: Boolean)
  : ActorGateway = {
    val jobManagerURL = AkkaUtils.getAkkaURL(actorSystem, jobManager)

    createTaskManager(
      actorSystem,
      jobManagerURL,
      configuration,
      useLocalCommunication,
      waitForRegistration
    )
  }

  def createTaskManager(
    actorSystem: ActorSystem,
    jobManager: ActorGateway,
    configuration: Configuration,
    useLocalCommunication: Boolean,
    waitForRegistration: Boolean,
    taskManagerClass: Class[_ <: TaskManager])
  : ActorGateway = {
    val jobManagerURL = AkkaUtils.getAkkaURL(actorSystem, jobManager.actor)

    createTaskManager(
      actorSystem,
      jobManagerURL,
      configuration,
      useLocalCommunication,
      waitForRegistration,
      taskManagerClass
    )
  }

  def createTaskManager(
      actorSystem: ActorSystem,
      jobManager: ActorGateway,
      configuration: Configuration,
      useLocalCommunication: Boolean,
      waitForRegistration: Boolean)
    : ActorGateway = {
    val jobManagerURL = AkkaUtils.getAkkaURL(actorSystem, jobManager.actor)

    createTaskManager(
      actorSystem,
      jobManagerURL,
      configuration,
      useLocalCommunication,
      waitForRegistration
    )
  }

  /** Creates a local TaskManager in the given ActorSystem. It is given a
    * [[StandaloneLeaderRetrievalService]] which returns the given jobManagerURL. After creating
    * the TaskManager, waitForRegistration specifies whether one waits until the TaskManager has
    * registered at the JobManager. An ActorGateway to the TaskManager is returned.
    *
    * @param actorSystem ActorSystem in which the TaskManager shall be started
    * @param jobManagerURL URL of the JobManager to connect to
    * @param configuration Configuration
    * @param useLocalCommunication true if the network stack shall use exclusively local
    *                              communication
    * @param waitForRegistration true if the method will wait until the TaskManager has connected to
    *                            the JobManager
    * @return ActorGateway of the created TaskManager
    */
  def createTaskManager(
    actorSystem: ActorSystem,
    jobManagerURL: String,
    configuration: Configuration,
    useLocalCommunication: Boolean,
    waitForRegistration: Boolean)
  : ActorGateway = {
    createTaskManager(
      actorSystem,
      jobManagerURL,
      configuration,
      useLocalCommunication,
      waitForRegistration,
      classOf[TestingTaskManager]
    )
  }


  def createTaskManager(
      actorSystem: ActorSystem,
      jobManagerURL: String,
      configuration: Configuration,
      useLocalCommunication: Boolean,
      waitForRegistration: Boolean,
      taskManagerClass: Class[_ <: TaskManager])
    : ActorGateway = {

    val resultingConfiguration = new Configuration()

    resultingConfiguration.setInteger(ConfigConstants.TASK_MANAGER_MEMORY_SIZE_KEY, 10)

    resultingConfiguration.addAll(configuration)

    val leaderRetrievalService = Option(new StandaloneLeaderRetrievalService(jobManagerURL))

    val taskManager = TaskManager.startTaskManagerComponentsAndActor(
      resultingConfiguration,
      ResourceID.generate(),
      actorSystem,
      "localhost",
      None,
      leaderRetrievalService,
      useLocalCommunication,
      taskManagerClass
    )

    if (waitForRegistration) {
      val notificationResult = (taskManager ? NotifyWhenRegisteredAtJobManager)(TESTING_DURATION)

      Await.ready(notificationResult, TESTING_DURATION)
    }

    new AkkaActorGateway(taskManager, null)
  }

  /** Stops the given actor by sending it a Kill message
    *
    * @param actor
    */
  def stopActor(actor: ActorRef): Unit = {
    if (actor != null) {
      actor ! Kill
    }
  }

  /** Stops the given actro by sending it a Kill message
    *
    * @param actorGateway
    */
  def stopActor(actorGateway: ActorGateway): Unit = {
    if (actorGateway != null) {
      stopActor(actorGateway.actor())
    }
  }

  /** Creates a testing JobManager using the default recovery mode (standalone)
    *
    * @param actorSystem The ActorSystem to use
    * @param configuration The Flink configuration
    * @return
    */
  def createJobManager(
      actorSystem: ActorSystem,
      configuration: Configuration)
    : ActorGateway = {
    createJobManager(
      actorSystem,
      configuration,
      classOf[TestingJobManager],
      ""
    )
  }

  /** Creates a testing JobManager using the default recovery mode (standalone).
    * Additional prefix can be supplied for the Actor system names
    *
    * @param actorSystem The ActorSystem to use
    * @param configuration The Flink configuration
    * @param prefix The prefix for the actor names
    * @return
    */
  def createJobManager(
      actorSystem: ActorSystem,
      configuration: Configuration,
      prefix: String)
    : ActorGateway = {
    createJobManager(
      actorSystem,
      configuration,
      classOf[TestingJobManager],
      prefix
    )
  }

  def createJobManager(
      actorSystem: ActorSystem,
      configuration: Configuration,
      executionContext: ExecutionContext)
    : ActorGateway = {

    val (_,
    instanceManager,
    scheduler,
    libraryCacheManager,
    restartStrategy,
    timeout,
    archiveCount,
    leaderElectionService,
    submittedJobGraphs,
    checkpointRecoveryFactory,
    savepointStore,
    jobRecoveryTimeout,
    metricsRegistry) = JobManager.createJobManagerComponents(
      configuration,
      None
    )

    val archiveProps = Props(classOf[TestingMemoryArchivist], archiveCount)

    val archive: ActorRef = actorSystem.actorOf(archiveProps, JobManager.ARCHIVE_NAME)

    val jobManagerProps = Props(
      classOf[TestingJobManager],
      configuration,
      executionContext,
      instanceManager,
      scheduler,
      libraryCacheManager,
      archive,
      restartStrategy,
      timeout,
      leaderElectionService,
      submittedJobGraphs,
      checkpointRecoveryFactory,
      jobRecoveryTimeout,
      metricsRegistry)

    val jobManager: ActorRef = actorSystem.actorOf(jobManagerProps, JobManager.JOB_MANAGER_NAME)

    new AkkaActorGateway(jobManager, null)
  }

  /**
    * Creates a JobManager of the given class using the default recovery mode (standalone)
    *
    * @param actorSystem ActorSystem to use
    * @param configuration Configuration to use
    * @param jobManagerClass JobManager class to instantiate
    * @return
    */
  def createJobManager(
      actorSystem: ActorSystem,
      configuration: Configuration,
      jobManagerClass: Class[_ <: JobManager])
    : ActorGateway = {

    createJobManager(actorSystem, configuration, jobManagerClass, "")
  }

  /**
    * Creates a JobManager of the given class using the default recovery mode (standalone).
    * Additional prefix for the Actor names can be added.
    *
    * @param actorSystem ActorSystem to use
    * @param configuration Configuration to use
    * @param jobManagerClass JobManager class to instantiate
    * @param prefix The prefix to use for the Actor names
    *
    * @return
    */
  def createJobManager(
      actorSystem: ActorSystem,
      configuration: Configuration,
      jobManagerClass: Class[_ <: JobManager],
      prefix: String)
    : ActorGateway = {

    configuration.setString(ConfigConstants.HA_MODE,
      ConfigConstants.DEFAULT_HA_MODE)

      val (actor, _) = JobManager.startJobManagerActors(
        configuration,
        actorSystem,
        Some(prefix + JobManager.JOB_MANAGER_NAME),
        Some(prefix + JobManager.ARCHIVE_NAME),
        jobManagerClass,
        classOf[MemoryArchivist])

    new AkkaActorGateway(actor, null)
  }

  /** Creates a forwarding JobManager which sends all received message to the forwarding target.
    *
    * @param actorSystem The actor system to start the actor in.
    * @param forwardingTarget Target to forward to.
    * @param actorName Name for forwarding Actor
    * @return
    */
  def createForwardingActor(
      actorSystem: ActorSystem,
      forwardingTarget: ActorRef,
      actorName: Option[String] = None)
    : ActorGateway = {

    val actor = actorName match {
      case Some(name) =>
        actorSystem.actorOf(
          Props(
            classOf[ForwardingActor],
            forwardingTarget,
            None),
          name
        )
      case None =>
        actorSystem.actorOf(
          Props(
            classOf[ForwardingActor],
            forwardingTarget,
            None)
        )
    }

    new AkkaActorGateway(actor, null)
  }

  def submitJobAndWait(
      actorSystem: ActorSystem,
      jobManager: ActorGateway,
      jobGraph: JobGraph)
    : JobExecutionResult = {

    val jobManagerURL = AkkaUtils.getAkkaURL(actorSystem, jobManager.actor)
    val leaderRetrievalService = new StandaloneLeaderRetrievalService(jobManagerURL)

    JobClient.submitJobAndWait(
      actorSystem,
      leaderRetrievalService,
      jobGraph,
      TESTING_DURATION,
      false,
      Thread.currentThread().getContextClassLoader
    )
  }

  /** Creates a testing JobManager using the default recovery mode (standalone)
    *
    * @param actorSystem The actor system to start the actor
    * @param jobManager The jobManager for the standalone leader service.
    * @param configuration The configuration
    * @return
    */
  def createResourceManager(
      actorSystem: ActorSystem,
      jobManager: ActorRef,
      configuration: Configuration)
  : ActorGateway = {

    configuration.setString(ConfigConstants.HA_MODE,
      ConfigConstants.DEFAULT_HA_MODE)

    val actor = FlinkResourceManager.startResourceManagerActors(
      configuration,
      actorSystem,
      LeaderRetrievalUtils.createLeaderRetrievalService(configuration, jobManager),
      classOf[TestingResourceManager])

    new AkkaActorGateway(actor, null)
  }


  class ForwardingActor(val target: ActorRef, val leaderSessionID: Option[UUID])
    extends FlinkActor with LeaderSessionMessageFilter with LogMessages {

    /** Handle incoming messages
      *
      * @return
      */
    override def handleMessage: Receive = {
      case msg => target.forward(msg)
    }

    override val log: Logger = Logger(getClass)
  }

}
