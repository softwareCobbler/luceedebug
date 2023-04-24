package dwr.jdwp.packet.reply.event_request

import dwr.jdwp.packet._
import dwr.reader._
import scala.collection.IndexedSeqView

class Set(val requestID: Int) {}

object Set extends BodyFromWire[Set] {
    def bodyFromWire(body: IndexedSeqView[Byte])(using idSizes: IdSizes) : Set =
        val reader = JdwpSizedReader(idSizes, body)
        Set(requestID = reader.read_int32())
}
