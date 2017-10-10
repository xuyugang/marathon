package mesosphere.marathon

import java.util.{ Timer, TimerTask }

import akka.Done
import akka.actor.ActorRef
import akka.testkit.TestProbe
import mesosphere.AkkaUnitTest
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.core.deployment.DeploymentManager
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.heartbeat._
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.storage.repository.FrameworkIdRepository
import mesosphere.marathon.util.ScallopStub
import org.apache.mesos.{ SchedulerDriver, Protos => mesos }
import org.mockito.Matchers.{ eq => mockEq }
import org.mockito.Mockito
import org.mockito.Mockito.when
import org.scalatest.concurrent.Eventually

import scala.concurrent.Future
import scala.concurrent.duration._

object MarathonSchedulerServiceTest {
  import Mockito.mock

  val ReconciliationDelay = 5000L
  val ReconciliationInterval = 5000L
  val ScaleAppsDelay = 4000L
  val ScaleAppsInterval = 4000L
  val MaxActorStartupTime = 5000L
  val OnElectedPrepareTimeout = 3 * 60 * 1000L

  def mockConfig: MarathonConf = {
    val config = mock(classOf[MarathonConf])

    when(config.reconciliationInitialDelay).thenReturn(ScallopStub(Some(ReconciliationDelay)))
    when(config.reconciliationInterval).thenReturn(ScallopStub(Some(ReconciliationInterval)))
    when(config.scaleAppsInitialDelay).thenReturn(ScallopStub(Some(ScaleAppsDelay)))
    when(config.scaleAppsInterval).thenReturn(ScallopStub(Some(ScaleAppsInterval)))
    when(config.zkTimeoutDuration).thenReturn(1.second)
    when(config.maxActorStartupTime).thenReturn(ScallopStub(Some(MaxActorStartupTime)))
    when(config.onElectedPrepareTimeout).thenReturn(ScallopStub(Some(OnElectedPrepareTimeout)))

    config
  }
}

class MarathonSchedulerServiceTest extends AkkaUnitTest with Eventually {
  import MarathonSchedulerServiceTest._

  class Fixture() {
    val driver = mock[SchedulerDriver]
    val electionService = mock[ElectionService]
    val marathonInitializer = mock[MarathonInitializer]
    marathonInitializer.getDriverInstance returns Future.successful(driver)
    val probe: TestProbe = TestProbe()
    val heartbeatProbe: TestProbe = TestProbe()
    val healthCheckManager: HealthCheckManager = mock[HealthCheckManager]
    val config: MarathonConf = mockConfig
    val httpConfig: HttpConf = mock[HttpConf]
    val frameworkIdRepository: FrameworkIdRepository = mock[FrameworkIdRepository]
    val groupManager: GroupManager = mock[GroupManager]
    val taskTracker: InstanceTracker = mock[InstanceTracker]
    val marathonScheduler: MarathonScheduler = mock[MarathonScheduler]
    val schedulerActor: ActorRef = probe.ref
    val heartbeatActor: ActorRef = heartbeatProbe.ref
    val mockTimer: Timer = mock[Timer]
    val deploymentManager: DeploymentManager = mock[DeploymentManager]

    persistenceStore.sync() returns Future.successful(Done)
    groupManager.invalidateGroupCache() returns Future.successful(Done)
  }

  def driverFactory[T](provide: => SchedulerDriver): SchedulerDriverFactory = {
    new SchedulerDriverFactory {
      override def createDriver(): SchedulerDriver = provide
    }
  }

  "MarathonSchedulerService" should {
    "Start timer when elected" in new Fixture {
      val schedulerService = new MarathonSchedulerService(
        config,
        groupManager,
        system,
        deploymentManager,
        electionService,
        marathonInitializer,
        schedulerActor,
        heartbeatActor
      )
      schedulerService.timer = mockTimer

      schedulerService.onLeaderReady()

      verify(mockTimer).schedule(any[TimerTask], mockEq(ReconciliationDelay), mockEq(ReconciliationInterval))
    }

    "Cancel timer when defeated" in new Fixture {
      val schedulerService = new MarathonSchedulerService(
        config,
        groupManager,
        system,
        deploymentManager,
        electionService,
        marathonInitializer,
        schedulerActor,
        heartbeatActor
      ) {
        override def onLeaderReady(): Unit = ()
      }

      schedulerService.timer = mockTimer
      // schedulerService.driver = Some(driver)
      schedulerService.onLeaderStop()

      verify(mockTimer).cancel()
      assert(schedulerService.timer != mockTimer, "Timer should be replaced after leadership defeat")
      val hmsg = heartbeatProbe.expectMsgType[Heartbeat.Message]
      assert(Heartbeat.MessageDeactivate(MesosHeartbeatMonitor.sessionOf(driver)) == hmsg)
    }
  }
}
