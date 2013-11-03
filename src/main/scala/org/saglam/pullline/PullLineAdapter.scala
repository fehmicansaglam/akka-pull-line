package org.saglam.pullline

import scala.reflect.ClassTag
import akka.actor._
import scala.collection.mutable
import org.saglam.pullline.Messages._
import scala.util.Try

class PullLineAdapter[L: ClassTag, R: ClassTag](workerLocation: String,
  leftLocation: String, rightLocation: String, bufferSize: Int)
    extends Actor with ActorLogging {

  val worker = context.actorSelection(workerLocation)
  val left = context.actorSelection(leftLocation)
  val right = context.actorSelection(rightLocation)
  val rightBuffer = mutable.Queue.empty[R]

  override def postStop(): Unit = {
    log.info("PullLineWorker stopped")
    left ! PoisonPill
  }

  def empty: Receive = {
    case Pull =>
      log.debug("Work requested from {}", sender)
      left ! Pull

    case PullDone(result: L) =>
      worker ! Work(Some(result))

    case WorkDone(result: Try[R]) =>
      result.foreach { _result =>
        rightBuffer.enqueue(_result)
        right ! WorkIsReady
        context.become(ready)
      }
      left ! Pull

    case WorkIsReady =>
      left ! Pull

    case NoMoreData => context.become(noMoreData)
  }

  def ready: Receive = {
    case Pull =>
      log.debug("Work requested from {}", sender)
      sender ! PullDone(rightBuffer.dequeue())
      left ! Pull
      if (rightBuffer.isEmpty)
        context.become(empty)

    case PullDone(result: L) => worker ! Work(Some(result))

    case WorkDone(result: Try[R]) =>
      result.foreach(rightBuffer.enqueue(_))
      if (rightBuffer.size < bufferSize)
        left ! Pull

    case WorkIsReady => left ! Pull

    case NoMoreData => context.become(noMoreData)
  }

  def noMoreData: Receive = {
    case Pull =>
      log.debug("Work requested from {}", sender)
      if (rightBuffer.isEmpty) {
        sender ! NoMoreData
        worker ! PoisonPill
      } else {
        sender ! PullDone(rightBuffer.dequeue())
      }

    case WorkDone(result: Try[R]) =>
      result.foreach(rightBuffer.enqueue(_))

  }

  def receive = empty
}