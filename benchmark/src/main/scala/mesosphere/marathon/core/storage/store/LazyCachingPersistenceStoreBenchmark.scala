package mesosphere.marathon
package core.storage.store

import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.ActorMaterializer
import kamon.Kamon
import mesosphere.marathon.core.storage.store.impl.cache.LazyCachingPersistenceStore
import mesosphere.marathon.core.storage.store.impl.memory.{ Identity, InMemoryPersistenceStore, RamId }
import mesosphere.marathon.core.storage.store.impl.zk.ZkPersistenceStore
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import scala.concurrent.{ Await, Future, Promise }
import scala.concurrent.duration._

case class TestClass1(str: String, int: Int, version: OffsetDateTime)

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

  implicit def marshaller[V]: Marshaller[V, Identity] = Marshaller.opaque { a: V => Identity(a) }

  @SuppressWarnings(Array("AsInstanceOf"))
  implicit def unmarshaller[V]: Unmarshaller[Identity, V] =
    Unmarshaller.strict { a: Identity => a.value.asInstanceOf[V] }

  private def cachedInMemory = {
    LazyCachingPersistenceStore(new InMemoryPersistenceStore())
  }

  //  def zkStore: ZkPersistenceStore = {
  //    val root = UUID.randomUUID().toString
  //    val client = zkClient(namespace = Some(root))
  //    new ZkPersistenceStore(client, Duration.Inf, 8)
  //  }
  //  private def cachedZk = LazyCachingPersistenceStore(zkStore)

  case class Run[K, Category](
      concurrentStores: Int,
      store: PersistenceStore[K, Category, Identity]
  )(implicit ir: IdResolver[String, TestClass1, Category, K]) {
    val go = Promise[Done]
    val count = new AtomicInteger()

    val original = TestClass1("abc", 1, OffsetDateTime.now())

    // Fill up runs but don't execute them yet.
    // All start when the promise `go` succeeds.
    val storeRuns = {
      val tmp = Future.sequence((1 to concurrentStores).map { i =>
        count.incrementAndGet()
        go.future.flatMap { _ =>
          if (i % 1000 == 0) println(s"Store $i")
          store.store("task-1", original)
        }
      })

      // Sleep until all are ready to run.
      while (count.get() < concurrentStores) { Thread.sleep(100) }
      tmp
    }
    val getRuns = {
      val tmp = Future.sequence((1 to concurrentStores).map { i =>
        count.incrementAndGet()
        go.future.flatMap { _ =>
          store.get("task-1")
        }
      })

      // Sleep until all are ready to run.
      while (count.get() < concurrentStores) { Thread.sleep(100) }
      tmp
    }

    def start(): Unit = go.success(Done)

    def await() = Await.result(storeRuns.flatMap(_ => getRuns), 10.minutes)
  }

  @Benchmark
  def storeAndGetInMemory(hole: Blackhole): Unit = {
    val r = Run(1000000, cachedInMemory)
    r.start()
    hole.consume(r.await())
  }

  //  @Benchmark
  //  def storeZooKeeper(hole: Blackhole): Unit = {
  //    val r = Run(100, cachedInMemory)
  //    r.start()
  //    hole.consume(r.await())
  //  }

  @Setup(Level.Trial)
  def setup(): Unit = {
    Kamon.start()
  }

  @TearDown(Level.Trial)
  def shutdown(): Unit = {
    println("Shutting down...")
    Kamon.shutdown()
    system.terminate()
    Await.ready(system.whenTerminated, 15.seconds)
    println("...shutdown succeeded.")
  }
}
