package dwr.jdwp.packet.command.virtual_machine

import dwr.jdwp.packet.{IdSizes => _, *}
import dwr.jdwp.packet.reply
import scala.collection.IndexedSeqView

class IdSizes() extends JdwpCommand with BodyToWire {
    final val command = Command.VirtualMachine_IDSizes
    def bodyToWire()(using idSizes: reply.virtual_machine.IdSizes) : Array[Byte] = new Array[Byte](0)
}

object IdSizes extends BodyFromWire[IdSizes] {
    def bodyFromWire(body: IndexedSeqView[Byte])(using idSizes: reply.virtual_machine.IdSizes) : IdSizes = IdSizes()
}
