package dwr.jdwp.packet.reply.event_request

import dwr.jdwp.packet._
import dwr.reader._
import dwr.utils.{ByteWrangler}
import scala.collection.IndexedSeqView

class Clear() extends BodyToWire {
    val command = Command.EventRequest_Clear
    def bodyToWire()(using idSizes: IdSizes) : Array[Byte] = new Array[Byte](0)
}

object Clear extends BodyFromWire[Clear] {
    def bodyFromWire(body: IndexedSeqView[Byte])(using idSizes: IdSizes) : Clear = Clear()
}
