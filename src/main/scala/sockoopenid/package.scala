
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import scala.Array.canBuildFrom
import scala.Option.option2Iterable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import org.mashupbots.socko.events.HttpRequestEvent
import org.mashupbots.socko.events.HttpResponseStatus
import org.mashupbots.socko.handlers.HttpDataFactory
import org.mashupbots.socko.handlers.StaticContentHandler
import org.mashupbots.socko.handlers.StaticContentHandlerConfig
import org.mashupbots.socko.handlers.StaticFileRequest
import org.openid4java.consumer.ConsumerManager
import org.openid4java.consumer.InMemoryConsumerAssociationStore
import org.openid4java.consumer.InMemoryNonceVerifier
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import akka.actor.Props
import akka.util.Timeout
import io.netty.handler.codec.http.DefaultCookie
import io.netty.handler.codec.http.HttpHeaders
import io.netty.handler.codec.http.ServerCookieEncoder
import io.netty.handler.codec.http.multipart.Attribute
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder
import io.netty.handler.codec.http.multipart.InterfaceHttpData
import io.netty.handler.codec.http.multipart.InterfaceHttpData.HttpDataType
import sockoopenid.AuthenticatedSessions
import org.openid4java.message.ParameterList
import io.netty.handler.codec.http.QueryStringDecoder

package object sockoopenid {

  /**
   * Application and system config should be in a file for a real application.
   * Has the vanilla static content setup from socko example apps.
   * Has a socko-openid session for parameters of this demo.
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
	        nr-of-instances = 5
	      }
	    }
	  }
	}
    socko-openid {
      # vanilla session timeout due to user inactivity
      sessions-timeout = 60000 milliseconds
    
      # io threadpool size
      io-threadpool-size = 25
    
      # akka ask pattern timeout for query to cpu bound actor
      ask-timeout = 99 milliseconds
    
      # openid4java consumer manager nonce timeout
      nonce-timeout = 60000 milliseconds

      # openid4java discovery timeout
      discovery-timeout = 30000 milliseconds
    
      # return url for openid provider
      return-url = "https://localhost:8888/registerreturn"
    }
  """

  /**
   * Vanilla static content compression and caching from socko examples apps.
   *
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
  val cpuBoundThreadPool = new ExecutionContext {
    val threadPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    def execute(runnable: Runnable) {
      threadPool.submit(runnable)
    }
    def reportFailure(t: Throwable) {}
  }

  /**
   * timeout for cpu bound ask of an actor
   */
  lazy val askTimeout = Timeout(timeoutMilliseconds("socko-openid.ask-timeout"))

  /**
   * An execution context for doing IO work such as openid discovery.
   */
  lazy val ioBoundThreadPool = new ExecutionContext {
    val size: Int = config.getInt("socko-openid.io-threadpool-size")
    val threadPool = Executors.newFixedThreadPool(size);
    def execute(runnable: Runnable) {
      threadPool.submit(runnable)
    }
    def reportFailure(t: Throwable) {}
  }

  /**
   * Config helpers
   */

  def timeoutMilliseconds(key: String)(implicit config: Config): FiniteDuration = {
    val v = config.getMilliseconds(key)
    val d = Duration(v, TimeUnit.MILLISECONDS)
    d
  }

  /**
   * Hmtl form helpers
   */

  def formAttribute(key: String)(implicit event: HttpRequestEvent): String = {
    val decoder = new HttpPostRequestDecoder(HttpDataFactory.value, event.nettyHttpRequest)
    val data: InterfaceHttpData = decoder.getBodyHttpData(key);
    if (data.getHttpDataType() == HttpDataType.Attribute) {
      data.asInstanceOf[Attribute].getValue()
    } else ""
  }

  def parameters(implicit event: HttpRequestEvent): ParameterList = {
    import collection.JavaConversions._
    val uri = event.request.nettyHttpRequest.getUri()
    val decoder = new QueryStringDecoder(uri)
    val m = decoder.parameters().toMap map {
      case (key, list) => (key, list.head)
    }
    new ParameterList(m)
  }

  /**
   * Redirect helpers
   */

  def bounceTo(url: String)(implicit event: HttpRequestEvent): Unit = bounceTo(HttpResponseStatus.SEE_OTHER, url)

  def bounceTo(httpStatus: HttpResponseStatus, url: String)(implicit event: HttpRequestEvent) = {
    event.response.headers.put(HttpHeaders.Names.LOCATION, url)
    event.response.write(httpStatus)
  }

  /**
   * Sessions
   */

  val JSESSIONID = "JSESSIONID"

  // note the browser *MUST* see a https connection from it's side else it will refuse to pass the secure cookie
  def setSessionCookie(value: String)(implicit event: HttpRequestEvent): Unit = {
    val cookie = new DefaultCookie(JSESSIONID, value)
    cookie.setSecure(true)
    event.response.headers.append("Set-Cookie", ServerCookieEncoder.encode(cookie))
  }

  def clearSessionCookie(implicit event: HttpRequestEvent): Unit = {
    val cookie = new DefaultCookie(JSESSIONID, "deleted")
    cookie.setSecure(true)
    cookie.setDiscard(true)
    event.response.headers.append("Set-Cookie", ServerCookieEncoder.encode(cookie))
  }

  def cookies(event: HttpRequestEvent): Map[String, String] = {
    event.request.headers.get("Cookie") match {
      case None => Map.empty
      case Some(cookies) =>
        (cookies.split(";") map {
          case kv if kv.contains("=") =>
            val ab = kv.split("=")
            Some((ab(0).trim, ab(1).trim))
          case c =>
            None
        }).flatten.toMap
    }
  }

  def getSessionCookie()(implicit event: HttpRequestEvent): Option[String] = {
    cookies(event).get(JSESSIONID)
  }

  /**
   * Openid4Java
   */

  lazy val consumerManager = initConsumerManager

  def initConsumerManager = {
    val timeout = timeoutMilliseconds("socko-openid.nonce-timeout")
    val consumerManager = new ConsumerManager();
    consumerManager.setAssociations(new InMemoryConsumerAssociationStore())
    consumerManager.setNonceVerifier(new InMemoryNonceVerifier(timeout.toSeconds.toInt))
    consumerManager
  }

  lazy val discoveryTimeout = Timeout(timeoutMilliseconds("socko-openid.discovery-timeout"))

  lazy val returnUrl = config.getString("socko-openid.return-url")

  /**
   * ubiquitous items which create cognitive load reading the main app so are implicits for reading clarity
   */
  implicit val config = ConfigFactory.parseString(actorConfig)
  implicit val actorSystem = ActorSystem("SockoOpenIdActorSystem", config)

  /**
   * bootstrap the user sessions actor. note that as it is not a system actor it has to be looked up
   * /user/sessions which is a nice naming coincidence
   */

  actorSystem.actorOf(Props(classOf[AuthenticatedSessions], timeoutMilliseconds("socko-openid.sessions-timeout")), "sessions")

  def sessions = actorSystem.actorSelection("/user/sessions")

  /**
   * bootstrap the discovery sessions actor. note that as it is not a system actor it has to be looked up
   * /user/discoveries which is a nice naming coincidence
   */

  actorSystem.actorOf(Props(classOf[DiscoverySessions], timeoutMilliseconds("socko-openid.discovery-timeout")), "discoveries")

  def discoveries = actorSystem.actorSelection("/user/discoveries")

}