package akka.contrib.mailbox

import language.postfixOps
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import akka.actor.Props
import akka.actor.Actor
import scala.concurrent.duration._
import akka.actor.DeadLetter
import akka.actor.Terminated
import akka.testkit.EventFilter

object PeekMailboxSpec {
  case object Check
  class PeekActor(tries: Int) extends Actor {
    var togo = tries
    def receive = {
      case Check ⇒
        sender ! Check
        PeekMailboxExtension.ack()
      case msg ⇒
        sender ! msg
        if (togo == 0) throw new RuntimeException("DONTWANNA")
        togo -= 1
        PeekMailboxExtension.ack()
    }
    override def preRestart(cause: Throwable, msg: Option[Any]) {
      msg match {
        case Some("DIE") ⇒ context stop self // for testing the case of mailbox.cleanUp
        case _           ⇒
      }
    }
  }
}

class PeekMailboxSpec extends AkkaSpec("""
    peek-dispatcher {
      mailbox-type = "akka.contrib.mailbox.PeekMailboxType"
      max-tries = 3
    }
    """) with ImplicitSender {

  import PeekMailboxSpec._

  "A PeekMailbox" must {

    "retry messages" in {
      val a = system.actorOf(Props(new PeekActor(1)).withDispatcher("peek-dispatcher"))
      a ! "hello"
      expectMsg("hello")
      EventFilter[RuntimeException]("DONTWANNA", occurrences = 1) intercept {
        a ! "world"
      }
      expectMsg("world")
      expectMsg("world")
      a ! Check
      expectMsg(Check)
    }

    "put a bound on retries" in {
      val a = system.actorOf(Props(new PeekActor(0)).withDispatcher("peek-dispatcher"))
      EventFilter[RuntimeException]("DONTWANNA", occurrences = 3) intercept {
        a ! "hello"
      }
      a ! Check
      expectMsg("hello")
      expectMsg("hello")
      expectMsg("hello")
      expectMsg(Check)
    }

    "support cleanup" in {
      system.eventStream.subscribe(testActor, classOf[DeadLetter])
      val a = system.actorOf(Props(new PeekActor(0)).withDispatcher("peek-dispatcher"))
      watch(a)
      EventFilter[RuntimeException]("DONTWANNA", occurrences = 1) intercept {
        a ! "DIE" // stays in the mailbox
      }
      expectMsg("DIE")
      expectMsgType[DeadLetter].message must be("DIE")
      expectMsgType[Terminated].actor must be(a)
    }

  }

}