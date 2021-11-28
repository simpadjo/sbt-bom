import org.apache.ivy.core.IvyPatternHelper
import org.apache.ivy.plugins.parser.m2.PomReader
import org.apache.ivy.plugins.repository.url.URLResource
import sbt.librarymanagement.DependencyBuilders.OrganizationArtifactName
import sbt.{Def, _}
import sbt.Keys._
import sbt.librarymanagement.DependencyResolution
import sbt.librarymanagement.ivy.IvyDependencyResolution
import sbt.util.LogExchange

import java.util


object Bom {
  def apply(bomArtifact: ModuleID): Def.Setting[Bom] = {
    val name = s"bom_${bomArtifact.toString}"
    val key = SettingKey[Bom](name)
    key := {
      // Pick resolvers from the configuration, but building DependencyResolution manually.
      // Getting DependencyResolution from the settings doesn't download poms properly sometimes
      val ivyConfig = InlineIvyConfiguration()
        .withResolvers((update / resolvers).value.to)
        .withUpdateOptions((update / updateOptions).value)
      val depRes = IvyDependencyResolution(ivyConfig)

      BomReader.read(bomArtifact.pomOnly(), scalaBinaryVersion.value, depRes)
    }
  }

  implicit def addBomSyntax(dep: OrganizationArtifactName): BomSyntax = new BomSyntax(dep)

  class BomSyntax(dep: OrganizationArtifactName){
    def %(bom: Bom): ModuleID = dep % bom.version(dep)
  }

}

trait Bom{
  def version(dependency: OrganizationArtifactName): String
}

object BomReader {
  def read(bomArtifact: ModuleID, scalaBinaryVersion: String, resolver: DependencyResolution): Bom = {
    val pomFile = downloadPomIfNeeded(bomArtifact, resolver)
    val versions = extractVersions(pomFile, scalaBinaryVersion)
    mkBom(versions, scalaBinaryVersion)
  }

  //TODO: locking? download location? invalidation?
  def downloadPomIfNeeded(bomArtifact: ModuleID, resolver: DependencyResolution): File = {
    val dir = "target"
    val pomFilename = s"${bomArtifact.name}-${bomArtifact.revision}.pom"
    val expectedPomLocation = new File(s"${dir}/poms/${bomArtifact.organization}/${bomArtifact.name}/$pomFilename")

    //TODO: what logger to use?
    val logger = LogExchange.logger("bom")

    if(!expectedPomLocation.exists()){
      resolver.retrieve(resolver.wrapDependencyInModule(bomArtifact), new File(dir), logger) match {
        case Left(warnings) => {
          //TODO: logging
          println(warnings.resolveException.messages.size.toString)
          warnings.resolveException.messages.foreach(s => println(s))
          warnings.resolveException.printStackTrace()
          sys.error(s"Failed to retrieve BOM pom file: ${warnings.resolveException.getMessage}")
        }
        case Right(files) => {
          if(!expectedPomLocation.exists()){
            sys.error(
              s"""
                |Failed to retrieve BOM pom file.
                |Expected pom location: ${expectedPomLocation.getAbsolutePath}.
                |Files downloaded: ${files.size}
                |${files.map(_.getAbsolutePath).mkString("\n")}""".stripMargin)
          }
        }
      }
    }
    expectedPomLocation
  }

  def extractVersions(file: File, scalaBinaryVersion: String): Map[(String, String), String] = {
    import scala.collection.JavaConverters._
    val url = file.asURL
    val reader = new PomReader(url, new URLResource(url))
    val properties = {
      val p = new util.HashMap[String, String]()
      p.putAll(reader.getPomProperties.asInstanceOf[util.Map[String, String]])
      p.put("scala.compat.version", scalaBinaryVersion)
      p
    }

    def evaluate(expr: String): Option[String] = {
      val res = IvyPatternHelper.substituteVariables(expr, properties)
      if(res.contains("${")){
        //TODO: sbt logger
        println(s"Failed to resolve $expr. Ignoring this element.")
        None
      } else {
        Some(res)
      }
    }

    reader.getDependencyMgt.asScala.flatMap(el => {
      val dme = el.asInstanceOf[reader.PomDependencyMgtElement]
      for {
        group <- evaluate(dme.getGroupId)
        artifact <- evaluate(dme.getArtifactId)
        version <- evaluate(dme.getVersion)
      } yield ((group, artifact), version)
    }).toMap
  }

  def mkBom(versions: Map[(String, String), String], scalaBinaryVersion: String): Bom = {
    new Bom {
      override def version(dependency: OrganizationArtifactName): String = {
        val dummyArtifact = dependency % "whatever"
        val org = dummyArtifact.organization
        val name = dummyArtifact.name

        //Name may or may no include scala suffix. Let's try both variants
        //TODO: detect correctly if the suffix is needed based on crossVersion property
        val names = if(name.endsWith(s"_$scalaBinaryVersion")){
          Seq(name)
        } else {
          Seq(s"${name}_$scalaBinaryVersion", name)
        }

        names.flatMap(n => versions.get((org, n))).headOption
          .getOrElse(sys.error(s"Version for $dependency not found in BOM"))
      }
    }
  }
}