package akka.contrib.mailbox

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorContext
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.Props
import akka.actor.PoisonPill
import akka.dispatch.Envelope
import akka.dispatch.MailboxType
import akka.dispatch.MessageQueue
import akka.dispatch.QueueBasedMessageQueue
import akka.dispatch.UnboundedMessageQueueSemantics

object PeekMailboxExtension extends ExtensionId[PeekMailboxExtension] with ExtensionIdProvider {
  def lookup = this
  def createExtension(s: ExtendedActorSystem) = new PeekMailboxExtension(s)

  def ack()(implicit context: ActorContext): Unit = {
    PeekMailboxExtension(context.system).ack()
  }
}

class PeekMailboxExtension(val system: ExtendedActorSystem) extends Extension {
  private val mailboxes = new ConcurrentHashMap[ActorRef, PeekMailbox]

  def register(actorRef: ActorRef, mailbox: PeekMailbox): Unit =
    mailboxes.put(actorRef, mailbox)

  def unregister(actorRef: ActorRef): Unit = mailboxes.remove(actorRef)

  def ack()(implicit context: ActorContext): Unit = {
    mailboxes.get(context.self) match {
      case null    ⇒ throw new IllegalArgumentException("Mailbox not registered for: " + context.self)
      case mailbox ⇒ mailbox.ack()
    }
  }
}

/**
 * configure the mailbox via dispatcher configuration:
 * {{{
 *   peek-dispatcher {
 *      mailbox-type = "example.PeekMailboxType"
 *   }
 * }}}
 */
class PeekMailboxType(settings: ActorSystem.Settings, config: Config) extends MailboxType {
  override def create(owner: Option[ActorRef], system: Option[ActorSystem]) = (owner, system) match {
    case (Some(o), Some(s)) ⇒
      val mailbox = new PeekMailbox(o, s, config.getInt("max-tries"))
      PeekMailboxExtension(s).register(o, mailbox)
      mailbox
    case _ ⇒ throw new Exception("no mailbox owner or system given")
  }
}

class PeekMailbox(owner: ActorRef, system: ActorSystem, maxTries: Int)
  extends QueueBasedMessageQueue with UnboundedMessageQueueSemantics {
  final val queue = new ConcurrentLinkedQueue[Envelope]()

  // the mutable state is only ever accessed by the actor (i.e. dequeue() side)
  var tries = 0
  val Marker = maxTries + 1

  override def dequeue(): Envelope = tries match {
    case -1         ⇒ queue.poll()
    case 0 | Marker ⇒ tries = 1; queue.peek()
    case `maxTries` ⇒ tries = Marker; queue.poll()
    case n          ⇒ tries = n + 1; queue.peek()
  }

  def ack(): Unit = {
    if (tries != Marker) queue.poll()
    tries = 0
  }

  override def cleanUp(owner: ActorRef, deadLetters: MessageQueue): Unit = {
    tries = -1 // put the queue into auto-ack mode
    super.cleanUp(owner, deadLetters)
    PeekMailboxExtension(system).unregister(owner)
  }
}

//#demo
class MyActor extends Actor {
  def receive = {
    case msg ⇒
      println(msg)
      doStuff(msg) // may fail
      PeekMailboxExtension.ack()
  }

  //#business-logic-elided
  var i = 0
  def doStuff(m: Any) {
    if (i == 1) throw new Exception("DONTWANNA")
    i += 1
  }

  override def postStop() {
    context.system.shutdown()
  }
  //#business-logic-elided
}

object MyApp extends App {
  val system = ActorSystem("MySystem", ConfigFactory.parseString("""
    peek-dispatcher {
      mailbox-type = "akka.contrib.mailbox.PeekMailboxType"
      max-tries = 2
    }
    """))

  val myActor = system.actorOf(Props[MyActor].withDispatcher("peek-dispatcher"),
    name = "myActor")

  myActor ! "Hello"
  myActor ! "World"
  myActor ! PoisonPill
}
//#demo