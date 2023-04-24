package dwr.jdwp.packet.command.virtual_machine

import dwr.jdwp.ThreadID
import dwr.jdwp.packet.*
import dwr.reader.*
import dwr.utils.ByteWrangler

import scala.collection.IndexedSeqView
import scala.collection.mutable.ArrayBuffer

class Suspend() extends JdwpCommand with BodyToWire {
  val command = Command.VirtualMachine_Suspend
  def bodyToWire()(using idSizes: IdSizes) : Array[Byte] = new Array[Byte](0)
}

object Suspend extends BodyFromWire[Suspend] {
  def bodyFromWire(body: IndexedSeqView[Byte])(using idSizes: IdSizes) : Suspend = Suspend()
}
