name := "permigo-api"

scalaVersion := "2.11.8"

resolvers += Resolver.bintrayRepo("hseeberger", "maven")

libraryDependencies ++= {
  object Versions {
    val circe = "0.7.0"
  }

  Seq(
    "com.typesafe.akka" %% "akka-http" % "10.0.3",
    "de.heikoseeberger" %% "akka-http-circe" % "1.12.0",
    "io.circe" %% "circe-generic" % Versions.circe,
    "io.circe" %% "circe-parser" % Versions.circe,
    "net.ruippeixotog" %% "scala-scraper" % "1.2.0"
  )
}
