name := "debiki-dao-pgsql"

organization := "com.debiki"

version := "0.0.2-SNAPSHOT"

scalaVersion := "2.10.0-RC1"

// You can download JDBC for Oracle 11.2.0.1 here:
// http://www.oracle.com/technetwork/database/enterprise-edition/
//   jdbc-112010-090769.html

libraryDependencies ++= Seq(
  "junit" % "junit" % "4.7" % "test",
  // The test suite needs a sl4j implementation or it logs nothing on errors:
  "ch.qos.logback" % "logback-classic" % "0.9.26" % "test",
  //"org.specs2" %% "specs2" % "1.12.2" % "test"  —> unresolved dependency: //"org.specs2#specs2_2.10;1.12.2: not found"
  "org.specs2" % "specs2_2.10.0-RC1" % "1.12.2" % "test"
)

// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list
