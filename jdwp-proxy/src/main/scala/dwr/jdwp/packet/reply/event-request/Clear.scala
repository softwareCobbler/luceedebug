package dwr.jdwp.packet.reply.event_request

import dwr.jdwp.packet._
import dwr.reader._
import dwr.utils.{ByteWrangler}

class Clear() extends BodyToWire {
    val command = Command.EventRequest_Clear
    def bodyToWire() : Array[Byte] = new Array[Byte](0)
}

object Clear extends FromWire[Clear] {
    def fromWire(idSizes: IdSizes, body: Array[Byte]) : Clear = Clear()
}
