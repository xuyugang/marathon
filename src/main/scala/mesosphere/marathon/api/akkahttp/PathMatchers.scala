package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.model.Uri.Path
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ Group, PathId, RootGroup, Timestamp }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.PathMatcher.{ Matched, Unmatched }
import akka.http.scaladsl.server.PathMatcher1

import scala.annotation.tailrec

object PathMatchers {

  /**
    * Matches the remaining path and transforms it into task id
    */
  final val RemainingTaskId = Remaining.map(s => Task.Id(s))

  /**
    * Tries to match the remaining path as Timestamp
    */
  final val Version = Segment.flatMap(string =>
    try Some(Timestamp(string))
    catch { case _: IllegalArgumentException ⇒ None }
  )

  /**
    * Given the current root group, only match and consume an existing appId
    *
    * This is useful because our v2 API has an unfortunate design decision which leads to ambiguity in our URLs, such as:
    *
    *   POST /v2/apps/my-group/restart/restart
    *
    * The intention here is to restart the app named "my-group/restart"
    *
    * Given the url above, this matcher will only consume "my-group/restart" from the path,
    * leaving the rest of the matcher to match the rest
    */
  case class ExistingAppPathId(rootGroup: RootGroup) extends PathMatcher1[PathId] {
    import akka.http.scaladsl.server.PathMatcher._

    @tailrec final def iter(reversePieces: List[String], remaining: Path, group: Group): Matching[Tuple1[PathId]] = remaining match {
      case Path.Slash(rest) =>
        iter(reversePieces, rest, group)
      case Path.Segment(segment, rest) =>
        val appended = segment :: reversePieces
        val pathId = PathId.sanitized(appended.reverse, true)
        if (group.groupsById.contains(pathId)) {
          iter(appended, rest, group.groupsById(pathId))
        } else if (group.apps.contains(pathId)) {
          Matched(rest, Tuple1(pathId))
        } else {
          Unmatched
        }
      case _ =>
        Unmatched
    }

    override def apply(path: Path) = iter(Nil, path, rootGroup)
  }

  /**
    * Path matcher, that matches a segment only, if it is defined in the given set.
    * @param set the allowed path segments.
    */
  class PathIsAvailableInSet(set: Set[String]) extends PathMatcher1[String] {
    def apply(path: Path) = path match {
      case Path.Segment(segment, tail) if set(segment) ⇒ Matched(tail, Tuple1(segment))
      case _ ⇒ Unmatched
    }
  }

}
