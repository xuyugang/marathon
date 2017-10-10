package mesosphere.marathon

import mesosphere.UnitTest

class MarathonInitializerTest extends UnitTest {
  // we need to test this too:
  // when(leadershipCoordinator.prepareForStart()).thenReturn(Future.successful(()))

  //     val prePostDriverCallbacks: Seq[PrePostDriverCallback] = Seq.empty

  //   "throw in start leadership when migration fails" in new Fixture {

  //   val schedulerService = new MarathonSchedulerService(
  //     config,
  //     prePostDriverCallbacks,
  //     groupManager,
  //     driverFactory(mock[SchedulerDriver]),
  //     system,
  //     migration,
  //     deploymentManager,
  //     schedulerActor,
  //     heartbeatActor
  //   )
  //   schedulerService.timer = mockTimer

  //   import java.util.concurrent.TimeoutException

  //   // use an Answer object here because Mockito's thenThrow does only
  //   // allow to throw RuntimeExceptions
  //   when(migration.migrate()).thenAnswer(new Answer[StorageVersion] {
  //     override def answer(invocation: InvocationOnMock): StorageVersion = {
  //       throw new TimeoutException("Failed to wait for future within timeout")
  //     }
  //   })

  //   intercept[TimeoutException] {
  //     schedulerService.startLeadership()
  //   }
  // }

  // "throw when the driver creation fails by some exception" in new Fixture {
  //   val driverFactory = mock[SchedulerDriverFactory]

  //   val schedulerService = new MarathonSchedulerService(
  //     config,
  //     prePostDriverCallbacks,
  //     groupManager,
  //     driverFactory,
  //     system,
  //     migration,
  //     deploymentManager,
  //     schedulerActor,
  //     heartbeatActor
  //   )

  //   schedulerService.timer = mockTimer

  //   when(leadershipCoordinator.prepareForStart()).thenReturn(Future.successful(()))
  //   when(driverFactory.createDriver()).thenThrow(new Exception("Some weird exception"))

  //   intercept[Exception] {
  //     schedulerService.startLeadership()
  //   }
  // }

  // "Abdicate leadership when driver ends with error" in new Fixture {
  //   val driver = mock[SchedulerDriver]
  //   val driverFactory = mock[SchedulerDriverFactory]

  //   val schedulerService = new MarathonSchedulerService(
  //     config,
  //     prePostDriverCallbacks,
  //     groupManager,
  //     driverFactory,
  //     system,
  //     migration,
  //     deploymentManager,
  //     schedulerActor,
  //     heartbeatActor
  //   )
  //   schedulerService.timer = mockTimer

  //   when(leadershipCoordinator.prepareForStart()).thenReturn(Future.successful(()))
  //   when(driverFactory.createDriver()).thenReturn(driver)

  //   when(driver.run()).thenThrow(new RuntimeException("driver failure"))

  //   schedulerService.startLeadership()
  //   eventually {
  //     schedulerService.leaderAbdicationRequested.isCompleted shouldBe true
  //   }
  // }

  // "Pre/post driver callbacks are called" in new Fixture {
  //   val cb = mock[PrePostDriverCallback]
  //   Mockito.when(cb.postDriverTerminates).thenReturn(Future(()))
  //   Mockito.when(cb.preDriverStarts).thenReturn(Future(()))

  //   val driver = mock[SchedulerDriver]
  //   val driverFactory = mock[SchedulerDriverFactory]

  //   val schedulerService = new MarathonSchedulerService(
  //     config,
  //     scala.collection.immutable.Seq(cb),
  //     groupManager,
  //     driverFactory,
  //     system,
  //     migration,
  //     deploymentManager,
  //     schedulerActor,
  //     heartbeatActor
  //   )
  //   schedulerService.timer = mockTimer

  //   when(leadershipCoordinator.prepareForStart()).thenReturn(Future.successful(()))
  //   when(driverFactory.createDriver()).thenReturn(driver)

  //   val driverCompleted = new java.util.concurrent.CountDownLatch(1)
  //   when(driver.run()).thenAnswer(new Answer[mesos.Status] {
  //     override def answer(invocation: InvocationOnMock): mesos.Status = {
  //       driverCompleted.await()
  //       mesos.Status.DRIVER_RUNNING
  //     }
  //   })

  //   schedulerService.startLeadership()

  //   val startOrder = Mockito.inOrder(migration, cb, driver)
  //   awaitAssert(startOrder.verify(migration).migrate())
  //   awaitAssert(startOrder.verify(cb).preDriverStarts)
  //   awaitAssert(startOrder.verify(driver).run())

  //   schedulerService.stopLeadership()
  //   awaitAssert(verify(driver).stop(true))

  //   driverCompleted.countDown()
  //   awaitAssert(verify(cb).postDriverTerminates)
  // }

}
