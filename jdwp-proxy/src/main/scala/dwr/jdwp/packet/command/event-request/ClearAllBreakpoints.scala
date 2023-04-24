package dwr.jdwp.packet.command.event_request

import dwr.jdwp.packet._
import dwr.reader._
import scala.collection.IndexedSeqView

class ClearAllBreakpoints() extends JdwpCommand {
    val command = Command.EventRequest_ClearAllBreakpoints
}

object ClearAllBreakpoints extends BodyFromWire[ClearAllBreakpoints] {
    def bodyFromWire(body: IndexedSeqView[Byte])(using idSizes: IdSizes) : ClearAllBreakpoints =
        ClearAllBreakpoints()
}
