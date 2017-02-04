name := "permigo-api"

scalaVersion := "2.11.8"

resolvers += Resolver.bintrayRepo("hseeberger", "maven")

libraryDependencies ++= {
  object Versions {
    val circe = "0.7.0"
  }

  Seq(
    // Main
    "com.typesafe.akka" %% "akka-http" % "10.0.3",
    "net.ruippeixotog" %% "scala-scraper" % "1.2.0",
    "org.sedis" %% "sedis" % "1.2.2",

    // JSON
    "de.heikoseeberger" %% "akka-http-circe" % "1.12.0",
    "io.circe" %% "circe-generic" % Versions.circe,
    "io.circe" %% "circe-parser" % Versions.circe,

    // Utils
    "com.github.melrief" %% "pureconfig" % "0.5.1",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0"
  )
}
