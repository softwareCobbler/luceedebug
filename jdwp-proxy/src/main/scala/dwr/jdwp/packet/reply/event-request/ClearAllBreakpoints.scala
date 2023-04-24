package dwr.jdwp.packet.reply.event_request

import dwr.jdwp.packet._
import dwr.reader._
import scala.collection.IndexedSeqView

class ClearAllBreakpoints() extends BodyToWire with JdwpReply {
    def bodyToWire()(using idSizes: IdSizes) : Array[Byte] = new Array[Byte](0)
}

object ClearAllBreakpoints extends BodyFromWire[ClearAllBreakpoints] {
    def bodyFromWire(body: IndexedSeqView[Byte])(using idSizes: IdSizes) : ClearAllBreakpoints =
        ClearAllBreakpoints()
}
