package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.testkit.ScalatestRouteTest
import mesosphere.UnitTest
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.test.GroupCreation

class DirectivesTest extends UnitTest with GroupCreation with ScalatestRouteTest {
  import PathId.StringPathId

  trait DirectivesTestFixture {
    val app1 = AppDefinition("/test/group1/app1".toPath)
    val app2 = AppDefinition("/test/group2/app2".toPath)
    val rootGroup = createRootGroup(
      groups = Set(
        createGroup("/test".toPath, groups = Set(
          createGroup("/test/group1".toPath, Map(app1.id -> app1)),
          createGroup("/test/group2".toPath, Map(app2.id -> app2))
        ))))

  }
}
