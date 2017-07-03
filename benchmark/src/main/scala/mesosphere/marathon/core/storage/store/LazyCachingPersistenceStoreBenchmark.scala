package mesosphere.marathon
package core.storage.store

import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.time.{ Instant, OffsetDateTime, ZoneOffset }
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.ActorMaterializer
import akka.util.ByteString
import kamon.Kamon
import mesosphere.marathon.core.storage.store.impl.cache.LazyCachingPersistenceStore
import mesosphere.marathon.core.storage.store.impl.memory.{ Identity, InMemoryPersistenceStore, RamId }
import mesosphere.marathon.core.storage.store.impl.zk.{ ZkId, ZkPersistenceStore, ZkSerialized }
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

trait ZkTestClass1Serialization {
  implicit object ZkTestClass1Resolver extends IdResolver[String, TestClass1, String, ZkId] {
    override def fromStorageId(path: ZkId): String = path.id.replaceAll("_", "/")
    override def toStorageId(id: String, version: Option[OffsetDateTime]): ZkId = {
      ZkId(category = "test-class", id.replaceAll("/", "_"), version)
    }
    override val category: String = "test-class"
    override val hasVersions = true
    override def version(tc: TestClass1): OffsetDateTime = tc.version
  }

  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  implicit val tc1ZkMarshal: Marshaller[TestClass1, ZkSerialized] =
    Marshaller.opaque { (a: TestClass1) =>
      val builder = ByteString.newBuilder
      val id = a.str.getBytes(StandardCharsets.UTF_8)
      builder.putInt(id.length)
      builder.putBytes(id)
      builder.putInt(a.int)
      builder.putLong(a.version.toInstant.toEpochMilli)
      builder.putInt(a.version.getOffset.getTotalSeconds)
      ZkSerialized(builder.result())
    }

  implicit val tc1ZkUnmarshal: Unmarshaller[ZkSerialized, TestClass1] =
    Unmarshaller.strict { (a: ZkSerialized) =>
      val it = a.bytes.iterator
      val len = it.getInt
      val str = it.getBytes(len)
      val int = it.getInt
      val time = it.getLong
      val offset = it.getInt
      val version = OffsetDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneOffset.ofTotalSeconds(offset))
      TestClass1(new String(str, StandardCharsets.UTF_8), int, version)
    }
}

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@Fork(1)
class LazyCachingPersistenceStoreBenchmark extends InMemoryTestClass1Serialization
    with ZkTestClass1Serialization
    with ZookeeperServerTest {

  //  import mesosphere.marathon.core.async.ExecutionContexts.global
  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val system: ActorSystem = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val scheduler = system.scheduler

  implicit def marshaller[V]: Marshaller[V, Identity] = Marshaller.opaque { a: V => Identity(a) }

  @SuppressWarnings(Array("AsInstanceOf"))
  implicit def unmarshaller[V]: Unmarshaller[Identity, V] =
    Unmarshaller.strict { a: Identity => a.value.asInstanceOf[V] }

  private def cachedInMemory = {
    LazyCachingPersistenceStore(new InMemoryPersistenceStore())
  }

  def zkStore: ZkPersistenceStore = {
    val root = UUID.randomUUID().toString
    val client = zkClient(namespace = Some(root))
    new ZkPersistenceStore(client, Duration.Inf, Integer.MAX_VALUE)
  }
  private def cachedZk = LazyCachingPersistenceStore(zkStore)

  case class Run[K, Category, Serialized](
      concurrentStores: Int,
      store: PersistenceStore[K, Category, Serialized]
  )(implicit
    ir: IdResolver[String, TestClass1, Category, K],
      marshaller: Marshaller[TestClass1, Serialized],
      unmarshaller: Unmarshaller[Serialized, TestClass1]) {
    val go = Promise[Done]
    val count = new AtomicInteger()
    val allDone = new AtomicBoolean(false)

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
    storeRuns.flatMap(_ => getRuns).foreach(_ => allDone.set(true))

    def start(): Unit = go.success(Done)

    def isDone(): Boolean = allDone.get()
  }

  @Benchmark
  def storeAndGetInMemory(hole: Blackhole): Unit = {
    val r = Run(1000000, cachedInMemory)
    r.start()

    // poll until we are done.
    while (!r.isDone()) { Thread.sleep(10.seconds.toMillis) }
  }

  //  @Benchmark
  //  def storeZooKeeper(hole: Blackhole): Unit = {
  //    val r = Run(100000, cachedZk)
  //    r.start()
  //
  //    // poll until we are done.
  //    while (!r.isDone()) { Thread.sleep(10.seconds.toMillis) }
  //  }

  @Setup(Level.Trial)
  def setup(): Unit = {
    Kamon.start()
    //    zkServer.start()
  }

  @TearDown(Level.Trial)
  def shutdown(): Unit = {
    println("Shutting down...")
    Kamon.shutdown()
    clients { c =>
      c.foreach(_.close())
      c.clear()
    }

    //    zkServer.close()
    system.terminate()
    Await.ready(system.whenTerminated, 15.seconds)
    println("...shutdown succeeded.")
  }
}
