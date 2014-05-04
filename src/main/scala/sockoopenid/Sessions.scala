package sockoopenid

import scala.util.Try
import scala.collection.immutable.Vector
import scala.collection.immutable.Map
import scala.util.Failure
import scala.util.Success
import scala.concurrent.duration.FiniteDuration
import akka.actor.Actor

class SessionsActor(timeout: FiniteDuration) extends Actor {
  import scala.concurrent.duration._

  var sessions: Sessions[String, String] = Sessions(timeout)

  def receive = {
    case key: String => {
      System.out.println("sending simbo1905")
      sender ! "simbo1905"
    }
  }
}

/**
 * Sessions[K, V] is an immutable key-value collection where
 * entries expire if they are not accessed or refreshed within
 * a given period.
 * Typically the three operators "+", "-" and "apply" manipulate
 * or access the data structure.
 * Adapted with changes from Jamie Pullar's Sessions at
 * http://higher-state.blogspot.co.uk/2013/02/session-state-in-scala1-immutable.html
 */
trait Sessions[K, V] {
  /**
   * Expiry time of entries in milliseconds
   * @return
   */
  def expiryInterval: Int

  /**
   * Returns the corresponding value for the key.
   * Does not refresh the session expiry time. Consider using apply
   * instead to increase the session time with mySessionState(someKey).
   * @param key
   * @param datetime current time in milliseconds
   * @return
   */
  def getValueNoRefresh(key: K)(implicit datetime: Long): Option[V]

  /**
   * Returns the corresponding value for the key and rejuvenates the
   * session increasing its expiry time to (datetime+expiryInterval).
   * Does rejuvenate the session expiry time. Overloaded as the "apply"
   * method so that you can invoke as mySessionState(someKey).
   * @param key
   * @param datetime current datetime in milliseconds
   * @return
   */
  def getValueWithRefresh(key: K)(implicit datetime: Long): (Sessions[K, V], Option[V])

  /**
   * Returns the corresponding value for the key and rejuvenates the
   * session increasing its expiry time to (datetime+ expiryInterval).
   * Does rejuvenate the session expiry time. Overloaded as the "apply"
   * method so that you can invoke as mySessionState(someKey).
   * @param sessionKey
   * @param datetime current datetime in milliseconds
   * @return
   */
  def apply(sessionKey: K)(implicit datetime: Long): (Sessions[K, V], Option[V]) =
    getValueWithRefresh(sessionKey)

  /**
   * Adds a session value and under a session key.
   * Does rejuvenate the session expiry time
   * @param sessionKey
   * @param value
   * @param datetime current datetime in milliseconds
   * @return New Sessions if successful, else SessionAlreadyExistsException
   */
  def put(key: K, value: V)(implicit datetime: Long): Try[Sessions[K, V]]

  /**
   * @see Invokes put(key: K, value: V)
   * @param sessionKey
   * @param value
   * @param datetime current datetime in milliseconds
   * @return New Sessions if successfull, else SessionAlreadyExistsException
   */
  def +(sessionKey: K, value: V)(implicit datetime: Long): Try[Sessions[K, V]] =
    put(sessionKey, value)

  /**
   * Removes the session value if found
   * @param key
   * @param datetime
   * @return New Sessions with the session key removed
   */
  def expire(sessionKey: K)(implicit datetime: Long): Sessions[K, V]

  /**
   * Removes the session value if found
   * @param key
   * @param datetime
   * @return New Sessions with the session key removed
   */
  def -(sessionKey: K)(implicit datetime: Long): Sessions[K, V] =
    expire(sessionKey)
}

object Sessions {
  def apply[K, V](duration: FiniteDuration): Sessions[K, V] =
    SessionStateInstance[K, V](duration.toMillis.toInt, Vector.empty, Map.empty)
}

private case class SessionStateInstance[K, V](expiryInterval: Int,
  sessionVector: Vector[(K, Long)], valuesWithExpiryMap: Map[K, (V, Long)])
  extends Sessions[K, V] {

  // vanilla access no refresh of expiry
  def getValueNoRefresh(sessionKey: K)(implicit datetime: Long): Option[V] =
    valuesWithExpiryMap.get(sessionKey) collect {
      case (value, expiry) if (expiry > datetime) => Some(value)
    } getOrElse (None)

  // gets the value and bumps the expiry by interval
  def getValueWithRefresh(sessionKey: K)(implicit datetime: Long): (Sessions[K, V], Option[V]) = {
    valuesWithExpiryMap.get(sessionKey) collect {
      case (value, expiry) =>
        (SessionStateInstance(this.expiryInterval, sessionVector,
          valuesWithExpiryMap + (sessionKey ->
            (value, datetime + this.expiryInterval))), Some(value))
    } getOrElse {
      (this, None)
    }
  }

  // puts a value into the session and expires any old session keys
  def put(sessionKey: K, value: V)(implicit datetime: Long): Try[Sessions[K, V]] =
    valuesWithExpiryMap.get(sessionKey) collect {
      case (value, expiry) if (expiry > datetime) =>
        Failure(SessionAlreadyExistsException)
    } getOrElse {
      val cleared = clearedExpiredSessions(datetime)
      Success(SessionStateInstance(this.expiryInterval,
        cleared.sessionVector :+ (sessionKey, datetime + this.expiryInterval),
        cleared.valuesWithExpiryMap + (sessionKey ->
          (value, datetime + this.expiryInterval))))
    }

  // fast delete can leave the queue and map out of sync which will be fixed up on next clear operation
  def expire(sessionKey: K)(implicit datetime: Long): Sessions[K, V] = {
    val cleared = clearedExpiredSessions(datetime)
    if (cleared.valuesWithExpiryMap.contains(sessionKey))
      SessionStateInstance(this.expiryInterval,
        cleared.sessionVector,
        cleared.valuesWithExpiryMap - sessionKey)
    else cleared
  }

  // used for unit testing
  def size = {
    valuesWithExpiryMap.size
  }

  private def clearedExpiredSessions(datetime: Long): SessionStateInstance[K, V] =
    clearedExpiredSessions(datetime, sessionVector, valuesWithExpiryMap)

  // forward scans the vector dropping expired values and puts it back 
  // into the same state as the updated map. halts when it finds unexpired 
  // values
  private def clearedExpiredSessions(now: Long, sessionQueue: Vector[(K, Long)], valuesWithExpiryMap: Map[K, (V, Long)]): SessionStateInstance[K, V] = {
    sessionQueue.headOption collect {
      // if we have an expired key
      case (key, end) if (end < now) =>
        // double check with map
        valuesWithExpiryMap.get(key) map {
          case (_, expiry) if (expiry < now) =>
            // drop the expired value
            clearedExpiredSessions(now, sessionQueue.drop(1), valuesWithExpiryMap - key)
          case (_, expiry) =>
            // push the key to the back and possibly out of order which will delay collection by up to interval
            clearedExpiredSessions(now, sessionQueue.drop(1) :+ (key, expiry), valuesWithExpiryMap)
        } getOrElse {
          // if it was not found in the map drop it from the front and check the next value
          clearedExpiredSessions(now, sessionQueue.drop(1), valuesWithExpiryMap)
        }
    } getOrElse {
      // no more expired keys at the front of the vector stop recursion
      SessionStateInstance(this.expiryInterval, sessionQueue, valuesWithExpiryMap)
    }
  }
}

case object SessionAlreadyExistsException extends Exception("Session key already exists")

case object SessionNotFound extends Exception("Session key not found")
