import java.time.format.DateTimeFormatter
import java.time.{LocalDate, ZoneOffset}
import com.typesafe.sbt.SbtNativePackager.{ Debian, Rpm }
import com.typesafe.sbt.packager.Keys._
import sbt.Keys._
import sbt._

object NativePackagerSettings {
  case class PackageVersionInfo(
    version: String,
    isSnapshot: Boolean)

  // Matches e.g. 1.5.1
  val releasePattern = """^(\d+)((?:\.\d+){2,3})$""".r
  // Matches e.g. 1.5.1-pre-42-gdeadbeef and 1.6.0-pre-42-gdeadbeef
  val snapshotPattern = """^(\d+)((?:\.\d+){2,3})(?:-SNAPSHOT|-pre)?-\d+-g(\w+)""".r


  def versionFor(version: String) = {
    version match {
      case releasePattern(major, rest) =>
        PackageVersionInfo(s"$major$rest", false)
      case snapshotPattern(major, rest, commit) =>
        PackageVersionInfo(s"$major$rest.${LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE)}git$commit", true)
      case v =>
        System.err.println(s"Version '$v' is not fully supported, please update the git tags.")
        PackageVersionInfo(v, true)
    }
  }

  /* This is to work around an issue with sbt-native-packager's start-debian-template, which caused no log output to be
   * captured. When https://github.com/sbt/sbt-native-packager/issues/1021 is fixed, then we can remove it (and the
   * template).
   */
  val debianSystemVSettings = Seq(
    (linuxStartScriptTemplate in Debian) :=
      (baseDirectory.value / "project" / "NativePackagerSettings/systemv/start-debian-template").toURI.toURL
  )

  /* This is to work around an issue with sbt-native-packager's start-template which caused /etc/default/marathon to be
   * ignored.  When https://github.com/sbt/sbt-native-packager/issues/1023 is fixed, then we can remove it (and the
   * template).
   */
  val ubuntuUpstartSettings = Seq(
    (linuxStartScriptTemplate in Debian) :=
      (baseDirectory.value / "project" / "NativePackagerSettings/upstart/start-template").toURI.toURL
  )
}
