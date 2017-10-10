package mesosphere.marathon

import akka.Done
import akka.actor.Cancellable
import com.typesafe.scalalogging.StrictLogging

import mesosphere.marathon.core.election.ElectionCandidate
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.storage.migration.Migration
import org.apache.mesos.SchedulerDriver
import scala.concurrent.{ ExecutionContext, Promise }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.Failure

/**
  * PrePostDriverCallback is implemented by callback receivers which have to listen for driver
  * start/stop events
  */
trait PrePostDriverCallback {
  /**
    * Will get called _before_ the driver is running, but after migration.
    */
  def preDriverStarts: Future[Unit]

  /**
    * Will get called _after_ the driver terminated
    */
  def postDriverTerminates: Future[Unit]
}

/**
  * Class responsible for initializing Marathon after it is elected leader, and before doing anything
  *
  * The methods startLeadership and stopLeadership are invoked the the ElectionService, before the any LeadershipState
  * messages are published
  */
class MarathonInitializer(
  persistenceStore: PersistenceStore[_, _, _],
  leadershipModule: LeadershipModule,
  config: MarathonConf,
  prePostDriverCallbacks: Seq[PrePostDriverCallback],
  groupManager: GroupManager,
  driverFactory: SchedulerDriverFactory,
  migration: Migration,
  electionEC: ExecutionContext)
    extends ElectionCandidate with StrictLogging {

  import mesosphere.marathon.core.async.ExecutionContexts.global

  // This is a little ugly as we are using a mutable variable. But drivers can't
  // be reused (i.e. once stopped they can't be started again. Thus,
  // we have to allocate a new driver before each run or after each stop.
  var driver: Option[SchedulerDriver] = None

  private val driverInstanceP = Promise[SchedulerDriver]
  def getDriverInstance = driverInstanceP.future

  private[this] def stopDriver(): Unit = synchronized {
    // many are the assumptions concerning when this is invoked. see startLeadership, stopLeadership,
    // triggerShutdown.
    logger.info("Stopping driver")

    // Stopping the driver will cause the driver run() method to return.
    driver.foreach(_.stop(true)) // failover = true

    // signals that the driver was stopped manually (as opposed to crashing mid-process)
    driver = None
  }

  // Used for legacy bootstrapping purposes
  // Some Guice initialized components require that thay are initialized _before_ the LeadershipObtained message
  private val readyToInitializeP = Promise[Unit]
  def readyToInitialize(): Unit = {
    /* Unblock the gate for the startLeadership.
     *
     * I don't like that this exists. This is a compromise made in order to avoid changing even more code in the leader
     * election / initialization patch. */
    readyToInitializeP.success(())
  }

  override def startLeadership(leaderCancellable: Cancellable): Future[Done] = readyToInitializeP.future.map { _ =>
    logger.info("As new leader running the driver")

    // allow interactions with the persistence store
    persistenceStore.markOpen()

    /* GroupManager and GroupRepository are holding in memory caches of the root group. The cache is loaded when it is
     * accessed the first time. Actually this is really bad, because each marathon will log the amount of groups during
     * startup through Kamon. Therefore the root group state is loaded from zk when the marathon instance is started.
     * When the marathon instance is elected as leader, this cache is still in the same state as the time marathon
     * started. Therefore we need to re-load the root group from zk again from zookeeper when becoming leader. The same
     * is true after doing the migration. A migration or a restore also affects the state of zookeeper, but does not
     * update the internal hold caches. Therefore we need to refresh the internally loaded caches after the
     * migration. Actually we need to do the fresh twice, before the migration, to perform the migration on the current
     * zk state and after the migration to have marathon loaded the current valid state to the internal caches. */

    // refresh group repository cache
    Await.result(groupManager.invalidateGroupCache(), Duration.Inf)

    // execute tasks, only the leader is allowed to
    migration.migrate()

    // refresh group repository again - migration or restore might changed zk state, this needs to be re-loaded
    Await.result(groupManager.invalidateGroupCache(), Duration.Inf)

    // run all pre-driver callbacks
    logger.info(s"""Call preDriverStarts callbacks on ${prePostDriverCallbacks.mkString(", ")}""")
    Await.result(
      Future.sequence(prePostDriverCallbacks.map(_.preDriverStarts)),
      config.onElectedPrepareTimeout().millis
    )
    logger.info("Finished preDriverStarts callbacks")

    // start all leadership coordination actors
    Await.result(leadershipModule.coordinator().prepareForStart(), config.maxActorStartupTime().milliseconds)

    // create new driver
    val driverInstance = driverFactory.createDriver()
    driverInstanceP.success(driverInstance)
    driver = Some(driverInstance)

    // The following block asynchronously runs the driver. Note that driver.run()
    // blocks until the driver has been stopped (or aborted).
    Future {
      scala.concurrent.blocking {
        driver.foreach(_.run())
      }
    } onComplete { result =>
      synchronized {

        logger.info(s"Driver future completed with result=$result.")
        result match {
          case Failure(t) => logger.error("Exception while running driver", t)
          case _ =>
        }

        // ONLY do this if there's some sort of driver crash: avoid invoking abdication logic if
        // the driver was stopped via stopDriver. stopDriver only happens when
        //   1. we're being terminated (and have already abdicated)
        //   2. we've lost leadership (no need to abdicate if we've already lost)
        driver.foreach { _ =>
          leaderCancellable.cancel()
        }

        driver = None

        logger.info(s"Call postDriverRuns callbacks on ${prePostDriverCallbacks.mkString(", ")}")
        Await.result(Future.sequence(prePostDriverCallbacks.map(_.postDriverTerminates)), config.zkTimeoutDuration)
        logger.info("Finished postDriverRuns callbacks")
      }
    }
    Done
  }(electionEC)

  override def stopLeadership(): Future[Done] = Future {
    // invoked by election service upon loss of leadership (state transitioned to Idle)
    logger.info("Lost leadership")

    // disallow any interaction with the persistence storage
    persistenceStore.markClosed()

    leadershipModule.coordinator().stop()

    driver.foreach { driverInstance =>
      stopDriver()
    }
    Done
  }(electionEC)
}
