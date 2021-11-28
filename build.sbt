ThisBuild / scalaVersion := "2.13.7"
ThisBuild / organization := "xyz.simpadjo"

lazy val myBom = Bom("org.springframework" % "spring-framework-bom" % "5.3.13")

lazy val hello = (project in file("."))
  .settings(
    name := "Bom-example",
    resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    myBom,
    libraryDependencies += "org.springframework" % "spring-core" % myBom.key.value
  )