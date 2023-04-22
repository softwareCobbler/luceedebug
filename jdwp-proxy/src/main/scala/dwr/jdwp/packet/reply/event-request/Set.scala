package dwr.jdwp.packet.reply.event_request

import dwr.jdwp.packet._
import dwr.reader._

class Set(val requestID: Int) {}

object Set extends BodyFromWire[Set] {
    def bodyFromWire(idSizes: IdSizes, body: Array[Byte]) : Set =
        val reader = JdwpSizedReader(idSizes, body)
        Set(requestID = reader.read_int32())
}
