package sockoopenid

import org.mashupbots.socko.routes._
import org.mashupbots.socko.infrastructure.Logger
import org.mashupbots.socko.webserver.WebServer
import org.mashupbots.socko.webserver.WebServerConfig
import akka.actor.ActorSystem
import akka.actor.Props
import org.mashupbots.socko.events.HttpRequestEvent
import akka.actor.Actor
import java.util.Date
import org.mashupbots.socko.webserver.SslConfig
import java.io.File
import org.mashupbots.socko.events.HttpResponseStatus
import scala.util.Failure
import scala.util.Success
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder
import org.mashupbots.socko.handlers.HttpDataFactory

object SockoOpenIdApp extends Logger {

  val routes = Routes({
    case HttpRequest(httpRequestEvent) => {
      implicit val event = httpRequestEvent
      event match {
        case Path("/") =>
          bounceTo("/public/index.html")

        case GET(PathSegments(List("public", fileName))) =>
          staticContentHandlerRouter ! fileInFolderRequest("public", fileName)

        case GET(PathSegments(List("private", fileName))) =>
          Authentication(event) onComplete {
            case Failure(ex) =>
              bounceTo(HttpResponseStatus.FORBIDDEN, "/public/403.html")
            case Success(value) =>
              System.out.println(s"got back the authentication $value")
              staticContentHandlerRouter ! fileInFolderRequest("private", fileName)
          }

        case POST(PathSegments(List("register"))) =>
          implicit val decoder = new HttpPostRequestDecoder(HttpDataFactory.value, event.nettyHttpRequest)
          val openId = formAttribute("openId")
          System.err.println(s"openId: $openId")
          bounceTo("/private/private.html")
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

