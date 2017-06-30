package mesosphere.marathon
package core.storage.store

import java.time.{ Clock, OffsetDateTime }
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import mesosphere.marathon.core.storage.store.impl.cache.LazyCachingPersistenceStore
import mesosphere.marathon.core.storage.store.impl.memory.{ InMemoryPersistenceStore, RamId }
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import scala.concurrent.Await
import scala.concurrent.duration._

case class TestClass1(str: String, int: Int, version: OffsetDateTime)

object TestClass1 {
  def apply(str: String, int: Int)(implicit clock: Clock): TestClass1 = {
    TestClass1(str, int, OffsetDateTime.now(clock))
  }
}

trait InMemoryTestClass1Serialization {
  implicit object InMemTestClass1Resolver extends IdResolver[String, TestClass1, String, RamId] {
    override def toStorageId(id: String, version: Option[OffsetDateTime]): RamId =
      RamId(category, id, version)
    override val category: String = "test-class"
    override val hasVersions = true

    override def fromStorageId(key: RamId): String = key.id
    override def version(v: TestClass1): OffsetDateTime = v.version
  }
}

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@Fork(1)
class LazyCachingPersistenceStoreBenchmark extends InMemoryTestClass1Serialization {

  import mesosphere.marathon.core.async.ExecutionContexts.global
  implicit val system: ActorSystem = ActorSystem()
  implicit val materializer = ActorMaterializer()

  private def cachedInMemory = {
    LazyCachingPersistenceStore(new InMemoryPersistenceStore())
  }

  @Benchmark
  def deploymentPlanDependencySpeed(hole: Blackhole): Unit = {
  }

  @TearDown(Level.Trial)
  def shutdown(): Unit = {
    system.terminate()
    Await.ready(system.whenTerminated, 15.seconds)
  }
}
