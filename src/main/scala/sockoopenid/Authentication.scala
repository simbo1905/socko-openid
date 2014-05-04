package sockoopenid

import org.mashupbots.socko.events.HttpRequestEvent
import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import com.typesafe.config.Config

case class Authentication(username: String)

object Authentication {
  def apply(httpRequestEvent: HttpRequestEvent)(implicit actorSystem: ActorSystem) = {
    actorSystem.actorSelection("/user/sessions") ? "some-key"
  }
}