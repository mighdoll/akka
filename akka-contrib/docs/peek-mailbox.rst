.. _mailbox-acking:

Mailbox with Explicit Acknowledgement
=====================================

The normal behavior of an Akka actor is to drop a message during whose
processing an exception occurred, continuing after a restart with dequeuing the
following message. This is in some cases not the desired solution, e.g. when
using failure and supervision to manage a connection to an unreliable resource;
the actor could after the restart go into a buffering mode and retry the real
processing later, when the unreliable resource is back online.

One way to do this is by buffering the messages in the supervisor and
acknowledging successful processing in the child, another way is to build an
explicit acknowledgement mechanism into the mailbox. The idea with the latter
is that a message is reprocessed in case of failure until the mailbox is told
that processing was successful.

The pattern is implemented `here
<@github@/akka-contrib/src/main/scala/akka/contrib/mailbox/PeekMailbox.scala>`_.
A demonstration of how to use it (although for brevity not a perfect example)
is the following:

.. includecode:: @contribSrc@/src/main/scala/akka/contrib/mailbox/PeekMailbox.scala
   :include: demo
   :exclude: business-logic-elided

Running this application may produce the following output (note the processing
of “World” on lines 2 and 16):

.. code-block:: none

   Hello
   World
   [ERROR] [12/17/2012 16:28:36.581] [MySystem-peek-dispatcher-5] [akka://MySystem/user/myActor] DONTWANNA
   java.lang.Exception: DONTWANNA
   	at akka.contrib.mailbox.MyActor.doStuff(PeekMailbox.scala:105)
   	at akka.contrib.mailbox.MyActor$$anonfun$receive$1.applyOrElse(PeekMailbox.scala:98)
   	at akka.actor.ActorCell.receiveMessage(ActorCell.scala:425)
   	at akka.actor.ActorCell.invoke(ActorCell.scala:386)
   	at akka.dispatch.Mailbox.processMailbox(Mailbox.scala:230)
   	at akka.dispatch.Mailbox.run(Mailbox.scala:212)
   	at akka.dispatch.ForkJoinExecutorConfigurator$MailboxExecutionTask.exec(AbstractDispatcher.scala:502)
   	at scala.concurrent.forkjoin.ForkJoinTask.doExec(ForkJoinTask.java:262)
   	at scala.concurrent.forkjoin.ForkJoinPool$WorkQueue.runTask(ForkJoinPool.java:975)
   	at scala.concurrent.forkjoin.ForkJoinPool.runWorker(ForkJoinPool.java:1478)
   	at scala.concurrent.forkjoin.ForkJoinWorkerThread.run(ForkJoinWorkerThread.java:104)
   World

Normally one would want to make processing idempotent (i.e. it does not matter
if a message is processed twice) or ``context.become`` a different behavior
upon restart; the above example included the ``println(msg)`` call just to
demonstrate the re-processing.
