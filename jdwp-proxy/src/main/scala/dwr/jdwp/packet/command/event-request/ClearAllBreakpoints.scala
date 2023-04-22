package dwr.jdwp.packet.command.event_request

import dwr.jdwp.packet._
import dwr.reader._

class ClearAllBreakpoints() extends JdwpCommand {
    val command = Command.EventRequest_ClearAllBreakpoints
}

object ClearAllBreakpoints extends BodyFromWire[ClearAllBreakpoints] {
    def bodyFromWire(idSizes: IdSizes, body: Array[Byte]) : ClearAllBreakpoints =
        ClearAllBreakpoints()
}
