package dwr.jdwp.packet.reply.event_request

import dwr.jdwp.packet._
import dwr.reader._

class ClearAllBreakpoints() extends BodyToWire {
    def bodyToWire()(using idSizes: IdSizes) : Array[Byte] = new Array[Byte](0)
}

object ClearAllBreakpoints extends BodyFromWire[ClearAllBreakpoints] {
    def bodyFromWire(idSizes: IdSizes, body: Array[Byte]) : ClearAllBreakpoints =
        ClearAllBreakpoints()
}
