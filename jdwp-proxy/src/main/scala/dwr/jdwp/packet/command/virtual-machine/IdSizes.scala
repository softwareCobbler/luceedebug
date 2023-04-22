package dwr.jdwp.packet.command.virtual_machine

import dwr.jdwp.packet.{IdSizes => _, *}
import dwr.jdwp.packet.reply

class IdSizes() extends JdwpCommand with BodyToWire {
    final val command = Command.VirtualMachine_IDSizes
    def bodyToWire() = new Array[Byte](0)
}

object IdSizes extends FromWire[JdwpCommand] {
    def fromWire(idSizes: reply.virtual_machine.IdSizes, body: Array[Byte]) : IdSizes = IdSizes()
}
