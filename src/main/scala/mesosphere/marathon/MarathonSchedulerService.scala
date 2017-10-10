package mesosphere.marathon

import java.util.concurrent.CountDownLatch
import java.util.{ Timer, TimerTask }
import javax.inject.{ Inject, Named }

import akka.Done
import akka.actor.{ ActorRef, ActorSystem }
import akka.stream.Materializer
import akka.util.Timeout
import com.google.common.util.concurrent.AbstractExecutionThreadService
import mesosphere.marathon.MarathonSchedulerActor._
import mesosphere.marathon.core.deployment.{ DeploymentManager, DeploymentPlan, DeploymentStepInfo }
import mesosphere.marathon.core.election.{ ElectionService, LeadershipTransition }
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.heartbeat._
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }
import mesosphere.marathon.stream.Sink
import mesosphere.util.PromiseActor
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

/**
  * DeploymentService provides methods to deploy plans.
  */
// TODO (AD): do we need this trait?
trait DeploymentService {
  /**
    * Deploy a plan.
    * @param plan the plan to deploy.
    * @param force only one deployment can be applied at a time. With this flag
    *              one can control, to stop a current deployment and start a new one.
    * @return a failed future if the deployment failed.
    */
  def deploy(plan: DeploymentPlan, force: Boolean = false): Future[Done]

  def listRunningDeployments(): Future[Seq[DeploymentStepInfo]]
}

/**
  * Wrapper class for the scheduler
  */
class MarathonSchedulerService @Inject() (
  config: MarathonConf,
  groupManager: GroupManager,
  system: ActorSystem,
  deploymentManager: DeploymentManager,
  electionService: ElectionService,
  marathonInitializer: MarathonInitializer,
  @Named("schedulerActor") schedulerActor: ActorRef,
  @Named(ModuleNames.MESOS_HEARTBEAT_ACTOR) mesosHeartbeatActor: ActorRef)(implicit mat: Materializer)
    extends AbstractExecutionThreadService with DeploymentService {

  import mesosphere.marathon.core.async.ExecutionContexts.global

  implicit val zkTimeout = config.zkTimeoutDuration

  val isRunningLatch = new CountDownLatch(1)

  // Time to wait before trying to reconcile app tasks after driver starts
  val reconciliationInitialDelay =
    Duration(config.reconciliationInitialDelay(), MILLISECONDS)

  // Interval between task reconciliation operations
  val reconciliationInterval =
    Duration(config.reconciliationInterval(), MILLISECONDS)

  // Time to wait before trying to scale apps after driver starts
  val scaleAppsInitialDelay =
    Duration(config.scaleAppsInitialDelay(), MILLISECONDS)

  // Interval between attempts to scale apps
  val scaleAppsInterval =
    Duration(config.scaleAppsInterval(), MILLISECONDS)

  var _isLeader: Boolean = false
  def isLeader = _isLeader

  private[mesosphere] var timer = newTimer()

  val log = LoggerFactory.getLogger(getClass.getName)

  implicit val timeout: Timeout = 5.seconds

  protected def newTimer() = new Timer("marathonSchedulerTimer")

  def deploy(plan: DeploymentPlan, force: Boolean = false): Future[Done] = {
    log.info(s"Deploy plan with force=$force:\n$plan ")
    val future: Future[Any] = PromiseActor.askWithoutTimeout(system, schedulerActor, Deploy(plan, force))
    future.map {
      case DeploymentStarted(_) => Done
      case DeploymentFailed(_, t) => throw t
    }
  }

  def cancelDeployment(plan: DeploymentPlan): Unit =
    schedulerActor ! CancelDeployment(plan)

  def listAppVersions(appId: PathId): Seq[Timestamp] =
    Await.result(groupManager.appVersions(appId).map(Timestamp(_)).runWith(Sink.seq), config.zkTimeoutDuration)

  def listRunningDeployments(): Future[Seq[DeploymentStepInfo]] =
    deploymentManager.list()

  def getApp(appId: PathId, version: Timestamp): Option[AppDefinition] = {
    Await.result(groupManager.appVersion(appId, version.toOffsetDateTime), config.zkTimeoutDuration)
  }

  def killInstances(
    appId: PathId,
    instances: Seq[Instance]): Unit = {
    schedulerActor ! KillTasks(appId, instances)
  }

  override def run(): Unit = {
    log.info("Beginning run")

    electionService.leaderTransitionEvents.runForeach {
      case LeadershipTransition.LeaderElectedAndReady => onLeaderReady()
      case LeadershipTransition.LostLeadership => onLeaderStop()
    }

    // Block on the latch which will be countdown only when shutdown has been
    // triggered. This is to prevent run()
    // from exiting.
    scala.concurrent.blocking {
      isRunningLatch.await()
    }
    log.info("Completed run")
  }

  override def triggerShutdown(): Unit = synchronized {
    electionService.abdicateLeadership()

    // The countdown latch blocks run() from exiting. Counting down the latch removes the block.
    log.info("Removing the blocking of run()")
    isRunningLatch.countDown()

    super.triggerShutdown()
  }

  def onLeaderReady(): Unit = synchronized {
    _isLeader = true
    timer.schedule(
      new TimerTask {
        def run(): Unit = {
          if (isLeader) {
            schedulerActor ! ScaleRunSpecs
          } else log.info("Not leader therefore not scaling apps")
        }
      },
      scaleAppsInitialDelay.toMillis,
      scaleAppsInterval.toMillis
    )

    timer.schedule(
      new TimerTask {
        def run(): Unit = {
          if (isLeader) {
            schedulerActor ! ReconcileTasks
            schedulerActor ! ReconcileHealthChecks
          } else log.info("Not leader therefore not reconciling tasks")
        }
      },
      reconciliationInitialDelay.toMillis,
      reconciliationInterval.toMillis
    )
  }

  def onLeaderStop(): Unit = synchronized {
    timer.cancel()
    _isLeader = false
    marathonInitializer.getDriverInstance.foreach { driverInstance =>
      mesosHeartbeatActor ! Heartbeat.MessageDeactivate(MesosHeartbeatMonitor.sessionOf(driverInstance))
    }
  }

  //End ElectionDelegate interface
}
