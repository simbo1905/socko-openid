name := """socko-openid"""

version := "0.1"

scalaVersion := "2.10.3"

libraryDependencies ++= Seq(
  "org.mashupbots.socko" %% "socko-webserver" % "0.4.0",
  "org.openid4java" % "openid4java" % "0.9.8",
  "junit" % "junit" % "4.8" % "test->default",
  "org.mockito" % "mockito-core" % "1.9.5" % "test->default",
  "org.scalatest" % "scalatest_2.10" % "1.9.1" % "test",
  "com.typesafe.akka" %% "akka-testkit" % "2.2.3" % "test"
  )

