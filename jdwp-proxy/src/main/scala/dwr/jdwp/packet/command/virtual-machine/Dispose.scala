package dwr.jdwp.packet.command.virtual_machine

import dwr.jdwp.packet._
import dwr.jdwp.packet.reply
import scala.collection.IndexedSeqView

class Dispose() extends JdwpCommand with BodyToWire {
    final val command = Command.VirtualMachine_Dispose
    def bodyToWire()(using idSizes: reply.virtual_machine.IdSizes) : Array[Byte] = new Array[Byte](0)
}

object Dispose extends BodyFromWire[Dispose] {
    def bodyFromWire(body: IndexedSeqView[Byte])(using idSizes: reply.virtual_machine.IdSizes) : Dispose = Dispose()
}
