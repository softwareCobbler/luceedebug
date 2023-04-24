package dwr.jdwp.packet.command.thread_reference

import dwr.jdwp.ThreadID
import dwr.jdwp.packet.*
import dwr.reader.*
import dwr.utils.ByteWrangler

import scala.collection.IndexedSeqView
import scala.collection.mutable.ArrayBuffer

class Suspend(val threadID: ThreadID) extends JdwpCommand with BodyToWire {
  val command = Command.ThreadReference_Suspend
  def bodyToWire()(using idSizes: IdSizes) : Array[Byte] =
    val buffer = ArrayBuffer[Byte]()
    threadID.toBuffer(buffer)
    buffer.toArray
}

object Suspend extends BodyFromWire[Suspend] {
  def bodyFromWire(body: IndexedSeqView[Byte])(using idSizes: IdSizes) : Suspend =
    val reader = JdwpSizedReader(idSizes, body)
    Suspend(threadID = reader.readThreadID())
}
