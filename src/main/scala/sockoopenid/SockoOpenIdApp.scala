package sockoopenid

import java.io.File
import java.util.UUID

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import org.mashupbots.socko.infrastructure.Logger
import org.mashupbots.socko.routes.GET
import org.mashupbots.socko.routes.HttpRequest
import org.mashupbots.socko.routes.POST
import org.mashupbots.socko.routes.Path
import org.mashupbots.socko.routes.PathSegments
import org.mashupbots.socko.routes.Routes
import org.mashupbots.socko.webserver.SslConfig
import org.mashupbots.socko.webserver.WebServer
import org.mashupbots.socko.webserver.WebServerConfig
import org.openid4java.discovery.DiscoveryInformation
import org.openid4java.message.AuthSuccess
import org.openid4java.message.sreg.SRegMessage
import org.openid4java.message.sreg.SRegRequest
import org.openid4java.message.sreg.SRegResponse

import akka.actor.Actor
import akka.actor.ActorSelection.toScala
import akka.actor.actorRef2Scala
import akka.pattern.ask

sealed trait SessionState {
  def sessionId: String
}
case class Authentication(sessionId: String, openId: String, email: String) extends SessionState
case class Discovery(sessionId: String, openId: String, discovery: DiscoveryInformation) extends SessionState

/**
 * For readability the folders and file names are hardcoded.
 * The package object has examples of using the Config to unhardcode such things.
 */
object SockoOpenIdApp extends Logger {

  val routes = Routes({
    case HttpRequest(httpRequestEvent) => {
      implicit val event = httpRequestEvent
      event match {
        // default redirect to the public index
        case Path("/") | Path("/index.html") =>
          bounceTo("/public/index.html")

        // serve a public file
        case GET(PathSegments(Seq("public", fileName))) =>
          staticContentHandlerRouter ! fileInFolderRequest("public", fileName)

        // check user has authenticated session before allowing access to the private folder
        case GET(PathSegments(Seq("private", fileName))) =>
          getSessionCookie match {
            case Some(cookie) =>
              implicit val timeout = askTimeout
              implicit val ex = cpuBoundThreadPool
              val future = sessions ? cookie
              future.onSuccess {
                case Some(authentication) =>
                  log.info(s"user has authenticated session")
                  staticContentHandlerRouter ! fileInFolderRequest("private", fileName)
                case x =>
                  log.info(s"user has cookie but session timed out or was never there got back $x")
                  bounceTo("/public/403.html")
              }
              future.onFailure {
                case ex =>
                  log.info(s"did not get back a session $ex")
                  bounceTo("/public/403.html")
              }
            case None =>
              log.info(s"user has no session cookie")
              bounceTo("/public/403.html")
          }

        // clear the session cookie upon logout
        case GET(PathSegments(Seq("logout"))) =>
          log.info(s"logging user out")
          clearSessionCookie
          bounceTo("/public/index.html")

        // register a new user using openid in a big block of code to show what is going on
        case POST(PathSegments(Seq("registerauth"))) =>

          // will do http discovery output calls on a background thread
          implicit val executionContextIO = ioBoundThreadPool
          implicit val timeoutIO = discoveryTimeout

          // set a secure session cookie
          val sessionKey = UUID.randomUUID().toString.replace("-", "")
          setSessionCookie(sessionKey)

          val openId = formAttribute("openId")

          // run the http fetches on an io bound threadpool
          val associatedDiscoveriesFuture = future {
            log.info(s"performing discovery on $openId")
            val discoveries = consumerManager.discover(openId);
            log.info(s"performing association on $openId")
            val info = consumerManager.associate(discoveries);
            log.info(s"completed association for $openId")
            info
          }

          associatedDiscoveriesFuture onFailure {
            case t =>
              log.error(s"was not able to discover and associate on $openId due to $t")
              bounceTo("/public/registration.html")
          }

          associatedDiscoveriesFuture onSuccess {
            case discoveryInformation =>
              val d = Discovery(sessionKey, openId, discoveryInformation)
              log.info(s"successfully discovered $openId caching $d")
              discoveries ! d

              val authRequest = Try {
                log.info(s"creating authentication request")
                // authenticated doesn't authenticate it just builds the request in which we ask for the user email
                val authRequest = consumerManager.authenticate(discoveryInformation, returnUrl)
                val sRegRequest = SRegRequest.createFetchRequest();
                sRegRequest.addAttribute("email", false);
                authRequest.addExtension(sRegRequest);
                authRequest.getDestinationUrl(true)
              }

              authRequest match {
                case Failure(e) =>
                  log.error(s"was not able to build auth request on $openId due to $e")
                  bounceTo("/public/registration.html")
                case Success(opUrl) =>
                  log.info(s"successfully built auth request for $openId redirecting to $opUrl")
                  bounceTo(opUrl)
              }
          }

        // process the return post authentication
        case GET(PathSegments(Seq("registerreturn"))) =>
          implicit val executionContext = cpuBoundThreadPool
          implicit val timeout = askTimeout
          getSessionCookie match {
            case Some(sessionKey) =>
              val askFuture = discoveries ? sessionKey
              askFuture onSuccess {
                case Some(Discovery(_, openId, discoveryInfo)) =>
                  log.info(s"have discovery for $openId")
                  implicit val executionContextIO = ioBoundThreadPool
                  implicit val timeoutIO = verifyTimeout

                  val verifyFuture = future {
                    log.info(s"trying verify for $openId")
                    val verificationResult = consumerManager.verify(returnUrl, parameters, discoveryInfo)
                    val verifiedIdentifier = verificationResult.getVerifiedId();
                    val authSuccess: AuthSuccess = verificationResult.getAuthResponse().asInstanceOf[AuthSuccess]
                    val extension = authSuccess.getExtension(SRegMessage.OPENID_NS_SREG)
                    val sRegResponse = extension.asInstanceOf[SRegResponse]
                    val email = sRegResponse.getAttributeValue("email")
                    val authentication = Authentication(sessionKey, verifiedIdentifier.getIdentifier(), email)
                    log.info(s"verify confirmed email is ${email}")
                    authentication
                  }
                  verifyFuture onSuccess {
                    case authentication =>
                      sessions ! authentication
                      log.info(s"successfully verified $authentication under sessionKey $sessionKey")
                      bounceTo("/private/private.html")
                  }
                  verifyFuture onFailure {
                    case e =>
                      log.error(s"error during verify of $sessionKey : $e")
                      bounceTo("/public/registration.html")
                  }
                case unknown =>
                  log.error(s"unknown discovery message $unknown trying to resolve $sessionKey")
                  bounceTo("/public/registration.html")
              }
              askFuture onFailure {
                case t =>
                  log.error(s"failed to resolve sessionKey $sessionKey with exception $t")
                  bounceTo("/public/registration.html")
              }
            case None =>
              log.info(s"no session cookie")
              bounceTo("/public/registration.html")
          }
      }
    }
  })

  def main(args: Array[String]) {
    val keyStoreFile = new File("/tmp/myKeyStore")
    val keyStoreFilePassword = "password"

    if (!keyStoreFile.exists) {
      System.out.println("Cannot find keystore file: " + keyStoreFile.getAbsolutePath)
      System.out.println("")
      System.out.println("Please create the file using the command:")
      System.out.println("  keytool -genkey -keystore " + keyStoreFile.getAbsolutePath + " -keyalg RSA")
      System.out.println("    Enter keystore password: " + keyStoreFilePassword)
      System.out.println("    What is your first and last name? [press ENTER]")
      System.out.println("    What is the name of your organizational unit? [press ENTER]")
      System.out.println("    What is the name of your organization? [press ENTER]")
      System.out.println("    What is the name of your State or Province? [press ENTER]")
      System.out.println("    What is the two-letter country code for this unit? [press ENTER]")
      System.out.println("    Is CN=Unknown, OU=Unknown, O=Unknown, L=Unknown, ST=Unknown, C=Unknown correct? yes")
      System.out.println("    Enter key password for <mykey> [press ENTER]")
      System.out.println("")
      System.out.println("Web Server terminated")
      return
    }

    val sslConfig = SslConfig(keyStoreFile, keyStoreFilePassword, None, None)
    val webServer = new WebServer(WebServerConfig(ssl = Some(sslConfig)), routes, actorSystem)
    webServer.start()

    Runtime.getRuntime.addShutdownHook(new Thread {
      override def run { webServer.stop() }
    })

    System.out.println("Open your browser and navigate to https://localhost:8888")
    System.out.println("Because this is a self-signed certificate, you will see a warning form the browser: " +
      "The site's security certificate is not trusted!")
    System.out.println("Trust this certificate and proceed.")
  }

}

class SessionsActor(val name: String, timeout: FiniteDuration) extends Actor with Logger {
  import scala.concurrent.duration._
  implicit def currentTime: Long = System.currentTimeMillis

  log.info(s"$name timeout is $timeout")
  var sessions: Sessions[String, SessionState] = Sessions(timeout)

  def receive = {
    case sessionKey: String =>
      sessions = sessions(sessionKey) match {
        case (updatedSessions, Some(authentication)) =>
          sender ! Some(authentication)
          updatedSessions
        case (updatedSessions, None) =>
          sender ! None
          updatedSessions
      }
    case a: SessionState =>
      val sessionKey = a.sessionId
      (sessions - sessionKey) + (sessionKey, a) match {
        case Success(updatedSessions) =>
          sessions = updatedSessions
          System.out.println(s"have added $a under $sessionKey")
        case Failure(ex) =>
          log.error(s"failed to add $a")
      }
    case unknown =>
      log.error(s"unknown message $unknown")
  }
}

