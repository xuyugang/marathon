package mesosphere.marathon

import akka.stream.scaladsl.Source
import java.util.concurrent.{ ExecutorService, Executors, TimeUnit }
import javax.inject.Named

import akka.actor.SupervisorStrategy.Restart
import akka.actor._
import akka.event.EventStream
import akka.routing.RoundRobinPool
import com.google.inject._
import com.google.inject.name.Names
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.core.deployment.DeploymentManager
import mesosphere.marathon.core.election.{ CuratorElectionStream, ElectionService, ElectionServiceImpl, LeadershipState }
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.heartbeat._
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.storage.repository.{ DeploymentRepository, GroupRepository }
import mesosphere.marathon.util.LifeCycledCloseable
import mesosphere.util.state._
import org.apache.mesos.Scheduler
import org.slf4j.LoggerFactory
import scala.concurrent.ExecutionContext

import scala.concurrent.duration._
import scala.util.control.NonFatal

object ModuleNames {
  final val HOST_PORT = "HOST_PORT"

  final val ELECTION_BACKEND = "ELECTION_BACKEND"
  final val SERVER_SET_PATH = "SERVER_SET_PATH"
  final val HISTORY_ACTOR_PROPS = "HISTORY_ACTOR_PROPS"

  final val MESOS_HEARTBEAT_ACTOR = "MesosHeartbeatActor"
  final val ELECTION_EXECUTOR = "ELECTION_EXECUTOR"
}

class MarathonModule(conf: MarathonConf, http: HttpConf, actorSystem: ActorSystem)
    extends AbstractModule {

  val log = LoggerFactory.getLogger(getClass.getName)

  def configure(): Unit = {
    bind(classOf[MarathonConf]).toInstance(conf)
    bind(classOf[HttpConf]).toInstance(http)
    bind(classOf[LeaderProxyConf]).toInstance(conf)
    bind(classOf[ZookeeperConf]).toInstance(conf)

    bind(classOf[HomeRegionProvider]).to(classOf[MarathonScheduler]).in(Scopes.SINGLETON)

    bind(classOf[MarathonSchedulerDriverHolder]).in(Scopes.SINGLETON)
    bind(classOf[MarathonSchedulerService]).in(Scopes.SINGLETON)
    bind(classOf[DeploymentService]).to(classOf[MarathonSchedulerService])

    bind(classOf[String])
      .annotatedWith(Names.named(ModuleNames.SERVER_SET_PATH))
      .toInstance(conf.zooKeeperServerSetPath)
  }

  @Named("schedulerActor")
  @Provides
  @Singleton
  @Inject
  @SuppressWarnings(Array("MaxParameters"))
  def provideSchedulerActor(
    system: ActorSystem,
    groupRepository: GroupRepository,
    deploymentRepository: DeploymentRepository,
    healthCheckManager: HealthCheckManager,
    killService: KillService,
    launchQueue: LaunchQueue,
    driverHolder: MarathonSchedulerDriverHolder,
    electionService: ElectionService,
    eventBus: EventStream,
    schedulerActions: SchedulerActions,
    deploymentManager: DeploymentManager,
    @Named(ModuleNames.HISTORY_ACTOR_PROPS) historyActorProps: Props): ActorRef = {
    val supervision = OneForOneStrategy() {
      case NonFatal(_) => Restart
    }

    system.actorOf(
      MarathonSchedulerActor.props(
        groupRepository,
        schedulerActions,
        deploymentManager,
        deploymentRepository,
        historyActorProps,
        healthCheckManager,
        killService,
        launchQueue,
        driverHolder,
        electionService.leaderTransitionEvents,
        eventBus
      ).withRouter(RoundRobinPool(nrOfInstances = 1, supervisorStrategy = supervision)),
      "MarathonScheduler")
  }

  @Named(ModuleNames.HOST_PORT)
  @Provides
  @Singleton
  def provideHostPort: String = {
    val port = if (http.disableHttp()) http.httpsPort() else http.httpPort()
    "%s:%d".format(conf.hostname(), port)
  }

  @Provides
  @Singleton
  def provideActorSystem(): ActorSystem = actorSystem

  /* Reexports the `akka.actor.ActorSystem` as `akka.actor.ActorRefFactory`. It doesn't work automatically. */
  @Provides
  @Singleton
  def provideActorRefFactory(system: ActorSystem): ActorRefFactory = system

  @Provides
  @Singleton
  def provideEventBus(actorSystem: ActorSystem): EventStream = actorSystem.eventStream
}
