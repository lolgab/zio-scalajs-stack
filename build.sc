import mill._, mill.scalalib._, mill.scalalib.scalafmt._
import mill.scalajslib._, mill.scalajslib.api._
import mill.scalalib.api.Util.isScala3
import coursier.maven.MavenRepository

import $ivy.`com.lihaoyi::mill-contrib-docker:$MILL_VERSION`
import contrib.docker.DockerModule
import $ivy.`com.goyeau::mill-scalafix::0.2.11`
import com.goyeau.mill.scalafix.ScalafixModule
import $ivy.`io.github.davidgregory084::mill-tpolecat::0.3.2`
import io.github.davidgregory084.TpolecatModule
import $ivy.`io.github.alexarchambault.mill::mill-native-image::0.1.23`
import io.github.alexarchambault.millnativeimage.NativeImage
import $ivy.`com.carlosedp::mill-docker-nativeimage::0.2.0`
import com.carlosedp.milldockernative.DockerNative

object versions {
  val scala213        = "2.13.10"
  val scala3          = "3.2.1"
  val scalajs         = "1.12.0"
  val zio             = "2.0.5"
  val ziometrics      = "2.0.4"
  val ziohttp         = "0.0.3"
  val sttp            = "3.8.8"
  val organizeimports = "0.6.0"
  val semanticdb      = "4.5.13"
  val scalajsdom      = "2.3.0"
  val scalatest       = "3.2.15"
  val coursier        = "v2.1.0-RC4"
}

trait Common extends ScalaModule with TpolecatModule with ScalafmtModule with ScalafixModule {
  def sources = T.sources(
    millSourcePath / "src",
    millSourcePath / os.up / "shared" / "src",
  )
  def scalafixIvyDeps = Agg(ivy"com.github.liancheng::organize-imports:${versions.organizeimports}")
  def repositoriesTask = T.task { // Add snapshot repositories in case needed
    super.repositoriesTask() ++ Seq("oss", "s01.oss")
      .map(r => s"https://$r.sonatype.org/content/repositories/snapshots")
      .map(MavenRepository(_))
  }
}

// -----------------------------------------------------------------------------
// Projects
// -----------------------------------------------------------------------------

object backend
  extends Common    // Base config for the backend
  with NativeImage  // Build binary based on GrallVM Native Image
  with DockerModule // Build Docker images based on JVM using the app .jar
  with DockerNative // Build Docker images with app binary (GraalVM Native Image)
  with NativeImageConfig { // Uses config for Native image
  def scalaVersion         = versions.scala213
  def nativeImageClassPath = runClasspath()
  def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"dev.zio::zio:${versions.zio}",
    ivy"dev.zio::zio-http:${versions.ziohttp}",
    ivy"dev.zio::zio-metrics-connectors:${versions.ziometrics}",
    ivy"dev.zio::zio-logging:2.1.7",
  )
  def scalacPluginIvyDeps =
    super.scalacPluginIvyDeps() ++ (if (!isScala3(scalaVersion()))
                                      Agg(ivy"org.scalameta:::semanticdb-scalac:${versions.semanticdb}")
                                    else Agg.empty)
  def dockerImage = "docker.io/carlosedp/zioscalajs-backend"
  def dockerPort  = 8080
  object dockerNative extends DockerNativeConfig with NativeImageConfig {
    def nativeImageClassPath = runClasspath()
    def baseImage            = "debian"
    def tags                 = List(dockerImage)
    def exposedPorts         = Seq(dockerPort)
  }

  object docker extends DockerConfig {
    def tags         = List(dockerImage)
    def exposedPorts = Seq(dockerPort)
  }

  object test extends Tests with Common {
    def ivyDeps = Agg(
      ivy"dev.zio::zio-test:${versions.zio}",
      ivy"dev.zio::zio-test-sbt:${versions.zio}",
    )
    def testFramework = T("zio.test.sbt.ZTestFramework")
  }
}

// Shared config trait for Native Image and DockerNative build
trait NativeImageConfig extends NativeImage {
  def nativeImageName = "backend"
  def nativeImageGraalVmJvmId = T {
    sys.env.getOrElse("GRAALVM_ID", "graalvm-java17:22.3.0")
  }
  def nativeImageMainClass = "com.carlosedp.zioscalajs.backend.MainApp"
  // Options required by ZIO to be built by GraalVM
  // Ref. https://github.com/jamesward/hello-zio-http/blob/graalvm/build.sbt#L97-L108
  def nativeImageOptions = Seq(
    "--no-fallback",
    "--enable-url-protocols=http,https",
    "-Djdk.http.auth.tunneling.disabledSchemes=",
    "--install-exit-handlers",
    "--enable-http",
    "--initialize-at-run-time=io.netty.channel.DefaultFileRegion",
    "--initialize-at-run-time=io.netty.channel.epoll.Native",
    "--initialize-at-run-time=io.netty.channel.epoll.Epoll",
    "--initialize-at-run-time=io.netty.channel.epoll.EpollEventLoop",
    "--initialize-at-run-time=io.netty.channel.epoll.EpollEventArray",
    "--initialize-at-run-time=io.netty.channel.kqueue.KQueue",
    "--initialize-at-run-time=io.netty.channel.kqueue.KQueueEventLoop",
    "--initialize-at-run-time=io.netty.channel.kqueue.KQueueEventArray",
    "--initialize-at-run-time=io.netty.channel.kqueue.Native",
    "--initialize-at-run-time=io.netty.channel.unix.Limits",
    "--initialize-at-run-time=io.netty.channel.unix.Errors",
    "--initialize-at-run-time=io.netty.channel.unix.IovArray",
    // "--allow-incomplete-classpath",
  ) ++ (if (sys.props.get("os.name").contains("Linux")) Seq("--static") else Seq.empty)

  // Define parameters to have the Native Image to be built in Docker
  // generating a Linux binary to be packed into the container image.
  def isDockerBuild = T.input(T.ctx.env.get("DOCKER_NATIVEIMAGE") != None)
  def nativeImageDockerParams = T {
    if (isDockerBuild()) {
      Some(
        NativeImage.DockerParams(
          imageName = "ubuntu:18.04",
          prepareCommand = """apt-get update -q -y &&\
                             |apt-get install -q -y build-essential libz-dev locales --no-install-recommends
                             |locale-gen en_US.UTF-8
                             |export LANG=en_US.UTF-8
                             |export LANGUAGE=en_US:en
                             |export LC_ALL=en_US.UTF-8""".stripMargin,
          csUrl =
            s"https://github.com/coursier/coursier/releases/download/${versions.coursier}/cs-${sys.props.get("os.arch").get}-pc-linux.gz",
          extraNativeImageArgs = Nil,
        ),
      )
    } else { Option.empty[NativeImage.DockerParams] }
  }
}

object frontend extends ScalaJSModule with Common {
  def scalaVersion   = versions.scala3
  def scalaJSVersion = versions.scalajs
  def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"org.scala-js::scalajs-dom::${versions.scalajsdom}",
    ivy"com.softwaremill.sttp.client3::core::${versions.sttp}",
  )

  def scalaJSUseMainModuleInitializer = true
  def moduleKind                      = T(ModuleKind.ESModule)
  def moduleSplitStyle                = T(ModuleSplitStyle.SmallModulesFor(List("com.carlosedp.zioscalajs.frontend")))

  // These two tasks are used by Vite to get update path
  def fastLinkOut() = T.command(println(fastLinkJS().dest.path))
  def fullLinkOut() = T.command(println(fullLinkJS().dest.path))

  object test extends Tests with Common with TestModule.ScalaTest {
    // Test dependencies
    def ivyDeps = Agg(
      ivy"org.scalatest::scalatest::${versions.scalatest}",
    )
    def jsEnvConfig = T(JsEnvConfig.JsDom())
  }
}

// -----------------------------------------------------------------------------
// Global commands
// -----------------------------------------------------------------------------

// Toplevel commands
def runTasks(t: Seq[String])(implicit ev: eval.Evaluator) = T.task {
  mill.main.MainModule.evaluateTasks(
    ev,
    t.flatMap(x => x +: Seq("+")).flatMap(x => x.split(" ")).dropRight(1),
    mill.define.SelectMode.Separated,
  )(identity)
}
def lint(implicit ev: eval.Evaluator) = T.command {
  runTasks(Seq("__.fix", "mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources"))
}
def deps(ev: eval.Evaluator) = T.command {
  mill.scalalib.Dependency.showUpdates(ev)
}
