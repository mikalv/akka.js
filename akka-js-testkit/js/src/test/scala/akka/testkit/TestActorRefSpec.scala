/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import language.{ postfixOps, reflectiveCalls }
import org.scalatest.Matchers
import org.scalatest.{ BeforeAndAfterEach, WordSpec }
import akka.actor._
import akka.event.Logging.Warning
import scala.concurrent.{ Future, Promise }
import akka.concurrent.{ Await, BlockingEventLoop }
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.pattern.ask
import akka.dispatch.Dispatcher
import akka.concurrent.BlockingEventLoop
import scala.scalajs.js.annotation._

/**
 * Test whether TestActorRef behaves as an ActorRef should, besides its own spec.
 */
object TestActorRefSpec {

  var counter = 4 
  var p = Promise[Int]
  }
  //val thread = Thread.currentThread
  //var otherthread: Thread = null

  trait TActor extends Actor {
    def receive = new Receive {
      val recv = receiveT
      def isDefinedAt(o: Any) = recv.isDefinedAt(o)
      def apply(o: Any) {
        //if (Thread.currentThread ne thread)
        //  otherthread = Thread.currentThread
        recv(o)
      }
    }
    def receiveT: Receive
  }

  @JSExport
  class ReplyActor extends TActor {
    import context.system
    var replyTo: ActorRef = null

    def receiveT = {
      case "complexRequest" ⇒ {
        replyTo = sender()
        val worker = TestActorRef(Props[WorkerActor])
        worker ! "work"
      }
      case "complexRequest2" ⇒
        val worker = TestActorRef(Props[WorkerActor])
        worker ! sender()
      case "workDone"      ⇒ replyTo ! "complexReply"
      case "simpleRequest" ⇒ sender() ! "simpleReply"
    }
  }

  @JSExport
  class WorkerActor() extends TActor {
    def receiveT = {
      case "work" ⇒ 
        sender() ! "workDone"
        context stop self
      case replyTo: Promise[_] ⇒ replyTo.asInstanceOf[Promise[Any]].success("complexReply")
      case replyTo: ActorRef   ⇒ replyTo ! "complexReply"
    }
  }

  @JSExport
  class SenderActor(replyActor: ActorRef) extends TActor {

    def receiveT = {
      case "complex"  ⇒ replyActor ! "complexRequest"
      case "complex2" ⇒ replyActor ! "complexRequest2"
      case "simple"   ⇒ replyActor ! "simpleRequest"
      case "complexReply" ⇒ {
        TestActorRefSpec.counter -= 1
        if(TestActorRefSpec.counter == 0) TestActorRefSpec.p.success(0)
      }
      case "simpleReply" ⇒ {
        TestActorRefSpec.counter -= 1
        if(TestActorRefSpec.counter == 0) TestActorRefSpec.p.success(0)
      }
    }
  }

  @JSExport
  class Logger extends Actor {
    var count = 0
    var msg: String = _
    def receive = {
      case Warning(_, _, m: String) ⇒ count += 1; msg = m
    }
  }

  @JSExport
  class ReceiveTimeoutActor(target: ActorRef) extends Actor {
    context setReceiveTimeout 1.second
    def receive = {
      case ReceiveTimeout ⇒
        target ! "timeout"
        context stop self
    }
  }

  /**
   * Forwarding `Terminated` to non-watching testActor is not possible,
   * and therefore the `Terminated` message is wrapped.
   */
  @JSExport
  case class WrappedTerminated(t: Terminated)

//}

//@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class TestActorRefSpec extends AkkaSpec(/*"disp1.type=Dispatcher"*/) with BeforeAndAfterEach with DefaultTimeout {

  import TestActorRefSpec._

  //override def beforeEach(): Unit = otherthread = null

  private def assertThread(): Unit = () //otherthread should (be(null) or equal(thread))

  "A TestActorRef should be an ActorRef, hence it" must {

    "support nested Actor creation" when {

      "used with TestActorRef" in {
        BlockingEventLoop.switch
        val a = TestActorRef(Props(new Actor {
          val nested = TestActorRef(Props(new Actor { def receive = { case _ ⇒ } }))
          def receive = { case _ ⇒ sender() ! nested }
        }))
        a should not be (null)
        // @note BUG IN SCALA.JS, mapTo DOESN'T WORK val nested = Await.result((a ? "any").mapTo[ActorRef], timeout.duration)
        val nested = Await.result((a ? "any"), timeout.duration).asInstanceOf[ActorRef]
        nested should not be (null)
        a should not be theSameInstanceAs(nested)
        BlockingEventLoop.reset
      }

      "used with ActorRef" in {
        BlockingEventLoop.switch
        val a = TestActorRef(Props(new Actor {
          val nested = context.actorOf(Props(new Actor { def receive = { case _ ⇒ } }))
          def receive = { case _ ⇒ sender() ! nested }
        }))
        a should not be (null)
        // @note BUG IN SCALA.JS, mapTo DOESN'T WORK val nested = Await.result((a ? "any").mapTo[ActorRef], timeout.duration)
        val nested = Await.result((a ? "any"), timeout.duration).asInstanceOf[ActorRef]
        nested should not be (null)
        a should not be theSameInstanceAs(nested)
        BlockingEventLoop.reset
      }

      "support reply via sender()" in {
        BlockingEventLoop.switch

        val serverRef = TestActorRef(Props[ReplyActor])
        val clientRef = TestActorRef(Props(classOf[SenderActor], serverRef))

        counter = 4

        clientRef ! "complex"
        clientRef ! "simple"
        clientRef ! "simple"
        clientRef ! "simple"

        Await.result(p.future) should be(0)
        p = Promise[Int]

        counter = 4

        clientRef ! "complex2"
        clientRef ! "simple"
        clientRef ! "simple"
        clientRef ! "simple"

        Await.result(p.future) should be(0) 

        BlockingEventLoop.reset

        assertThread()
      }


    "stop when sent a poison pill" in {
        BlockingEventLoop.switch
        EventFilter[ActorKilledException]() intercept {
          val a = TestActorRef(Props[WorkerActor])
          val forwarder = system.actorOf(Props(new Actor {
            context.watch(a)
            def receive = {
              case t: Terminated ⇒ testActor forward WrappedTerminated(t)
              case x             ⇒ testActor forward x
            }
          }))
          a.!(PoisonPill)(testActor)
          expectMsgPF(5 seconds) {
            case WrappedTerminated(Terminated(`a`)) ⇒ true
          }
          a.isTerminated should be(true)
          assertThread()
        }
        BlockingEventLoop.reset
      }


      "restart when Killed" in {
        BlockingEventLoop.switch
        EventFilter[ActorKilledException]() intercept {
          counter = 2
          val p = Promise[Int]

          val boss = TestActorRef(Props(new TActor {
            val ref = TestActorRef(Props(new TActor {
              def receiveT = { case _ ⇒ }
              override def preRestart(reason: Throwable, msg: Option[Any]) { counter -= 1; if(counter == 0) p.success(0) }
              override def postRestart(reason: Throwable) { counter -= 1; if(counter == 0) p.success(0) }
            }), self, "child")

            override def supervisorStrategy =
              OneForOneStrategy(maxNrOfRetries = 5, withinTimeRange = 1 second)(List(classOf[ActorKilledException]))

            def receiveT = { case "sendKill" ⇒ ref ! Kill }
          }))

          boss ! "sendKill"

          //counter should be(0)
          Await.result(p.future, 5 seconds) should be(0)
          assertThread()
          
        }
        BlockingEventLoop.reset
      }


      "support futures" in {
        BlockingEventLoop.switch
        val a = TestActorRef[WorkerActor]
        val f = a ? "work"
        // CallingThreadDispatcher means that there is no delay
        //f should be('completed)
        Await.result(f, timeout.duration) should be("workDone")
        BlockingEventLoop.reset
      }

      "support receive timeout" in {
        BlockingEventLoop.switch
        val a = TestActorRef(new ReceiveTimeoutActor(testActor))
        expectMsg("timeout")
        BlockingEventLoop.reset
      }
    }
  }
    
  "A TestActorRef" must {

    "allow access to internals" in {
      BlockingEventLoop.switch
      val p = Promise[Int]
      val ref = TestActorRef(new TActor {
        var s: String = _
        def receiveT = {
          case x: String ⇒ s = x; p.success(0)
        }
      })
      ref ! "hallo"
      val actor = ref.underlyingActor
      Await.result(p.future)
      actor.s should be("hallo")
      BlockingEventLoop.reset
    }
  

    "set receiveTimeout to None" in {
      BlockingEventLoop.switch
      val a = TestActorRef[WorkerActor]
      a.underlyingActor.context.receiveTimeout should be theSameInstanceAs Duration.Undefined
      BlockingEventLoop.reset
    }
    /** @note IMPLEMENT IN SCALA.JS
    "set CallingThreadDispatcher" in {
      val a = TestActorRef[WorkerActor]
      a.underlying.dispatcher.getClass should be(classOf[CallingThreadDispatcher])
    }

    "allow override of dispatcher" in {
      val a = TestActorRef(Props[WorkerActor].withDispatcher("disp1"))
      a.underlying.dispatcher.getClass should be(classOf[Dispatcher])
    }*/

    
    "proxy receive for the underlying actor without sender()" in {
      val ref = TestActorRef[WorkerActor]
      ref.receive("work")
      ref.isTerminated should be(true)
    }

    /** @note IMPLEMENT IN SCALA.JS
    "proxy receive for the underlying actor with sender()" in {
      val ref = TestActorRef[WorkerActor]
      ref.receive("work", testActor)
      ref.isTerminated should be(true)
      expectMsg("workDone")
    }
*/
  }
}