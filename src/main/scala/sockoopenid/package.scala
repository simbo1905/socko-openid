
import org.mashupbots.socko.handlers.StaticContentHandlerConfig
import java.io.File
import org.mashupbots.socko.handlers.StaticFileRequest
import org.mashupbots.socko.events.HttpRequestEvent
import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors
import com.typesafe.config.Config
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import akka.actor.ActorSystem
import akka.actor.Props
import org.mashupbots.socko.handlers.StaticContentHandler
import com.typesafe.config.ConfigFactory
import akka.util.Timeout
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder
import io.netty.handler.codec.http.multipart.InterfaceHttpData
import io.netty.handler.codec.http.multipart.InterfaceHttpData.HttpDataType
import io.netty.handler.codec.http.multipart.Attribute
import io.netty.handler.codec.http.HttpHeaders
import org.mashupbots.socko.events.HttpResponseStatus

package object sockoopenid {

  /**
   * Threadpool for static files which will be compressed and cached in temporary folder.
   */
  val actorConfig = """
	static-pinned-dispatcher {
	  type=PinnedDispatcher
	  executor=thread-pool-executor
	}
	akka {
    loggers = ["akka.event.slf4j.Slf4jLogger"]
    loglevel = "INFO"
	  actor {
	    deployment {
	      /static-file-router {
	        router = round-robin
	        nr-of-instances = 25
	      }
	    }
	  }
	}
    socko-openid {
      sessions {
        # vanilla session timeout due to user inactivity
        timeout = 120 seconds
      }
      # akka ask pattern timeout for query to cpu bound actor
      ask-timeout = 99 milliseconds
    }
  """

  /**
   * By default server static content out of src/main/resources. Use -DHTML_CONTENT_PATH=/blah/blah to override this.
   */

  val contentPath = scala.util.Properties.envOrElse("HTML_CONTENT_PATH", "src/main/resources")
  val tempFile = File.createTempFile("sockoopenid", ".started")
  val contentDir = new File(contentPath);
  def fileInFolderRequest(folderName: String, fileName: String)(implicit httpRequestEvent: HttpRequestEvent) = {
    new StaticFileRequest(httpRequestEvent, new File(new File(contentDir, folderName), fileName))
  }

  val staticContentHandlerConfig = StaticContentHandlerConfig(
    rootFilePaths = Seq(contentDir.getAbsolutePath, tempFile.getParentFile.getAbsolutePath))

  lazy val staticContentHandlerRouter =
    actorSystem.actorOf(Props(new StaticContentHandler(staticContentHandlerConfig)).withDispatcher("static-pinned-dispatcher"), "static-file-router")

  /**
   * An execution context for doing cpu bound work but no IO.
   */
  implicit val ec = new ExecutionContext {
    val threadPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    def execute(runnable: Runnable) {
      threadPool.submit(runnable)
    }
    def reportFailure(t: Throwable) {}
  }

  /**
   * Config helpers
   */

  def timeoutMilliseconds(key: String)(implicit config: Config): FiniteDuration =
    Duration(config.getMilliseconds(key),
      TimeUnit.MILLISECONDS)

  def timeoutSeconds(key: String)(implicit config: Config): FiniteDuration =
    Duration(config.getMilliseconds(key),
      TimeUnit.SECONDS)

  /**
   * Hmtl form helpers
   */
  def formAttribute(key: String)(implicit decoder: HttpPostRequestDecoder): String = {
    val data: InterfaceHttpData = decoder.getBodyHttpData(key);
    if (data.getHttpDataType() == HttpDataType.Attribute) {
      data.asInstanceOf[Attribute].getValue()
    } else ""
  }

  /**
   * Redirect helper
   */
  def bounceTo(url: String)(implicit event: HttpRequestEvent): Unit = bounceTo(HttpResponseStatus.SEE_OTHER, url)

  def bounceTo(httpStatus: HttpResponseStatus, url: String)(implicit event: HttpRequestEvent) = {
    event.response.headers.put(HttpHeaders.Names.LOCATION, url)
    event.response.write(httpStatus)
  }

  /**
   * ubiquitous items which create cognitive load reading the main app so swept under the carpet
   */
  implicit val config = ConfigFactory.parseString(actorConfig)
  implicit val actorSystem = ActorSystem("SockoOpenIdActorSystem", config)
  implicit val askTimeout = Timeout(timeoutMilliseconds("socko-openid.ask-timeout"))

  /**
   * bootstrap the user sessions actor. note that as it is not a system actor it has to be looked up
   * /user/sessions which is a nice naming coincidence
   */
  actorSystem.actorOf(Props(classOf[SessionsActor], timeoutSeconds("socko-openid.sessions.timeout")), "sessions");

}