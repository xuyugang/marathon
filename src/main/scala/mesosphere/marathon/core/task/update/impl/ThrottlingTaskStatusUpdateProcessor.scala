package mesosphere.marathon
package core.task.update.impl

import mesosphere.marathon.core.async.ExecutionContexts
import mesosphere.marathon.core.task.update.TaskStatusUpdateProcessor
import mesosphere.marathon.util.WorkQueue
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future

object ThrottlingTaskStatusUpdateProcessor {
  /**
    * A tag used for dependency injection to disambiguate the dependencies of this processor from
    * other instances with the same type.
    */
  final val dependencyTag = "ThrottlingTaskStatusUpdateProcessor"
}

class ThrottlingTaskStatusUpdateProcessor(
  serializePublish: WorkQueue,
  wrapped: TaskStatusUpdateProcessor)
    extends TaskStatusUpdateProcessor {
  override def publish(status: TaskStatus): Future[Unit] = {
    serializePublish(wrapped.publish(status))(ExecutionContexts.global)
  }
}
