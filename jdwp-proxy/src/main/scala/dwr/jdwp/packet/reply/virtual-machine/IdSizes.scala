package dwr.jdwp.packet.reply.virtual_machine

import dwr.reader._
import dwr.jdwp.packet.{BodyFromWire}
import scala.collection.IndexedSeqView

class IdSizes(
    val fieldIDSize: Int,
    val methodIDSize: Int,
    val objectIDSize: Int,
    val referenceTypeIDSize: Int,
    val frameIDSize: Int
) {}

object IdSizes extends BodyFromWire[IdSizes] {
    /**
     * During VM connect, we don't know IdSizes, so we ask for it,
     * but we need an IdSizes to to call `bodyFromWire` to parse the IdSizes reply packet.
     * This is a placeholder for that situation.
     */
    def dummy : IdSizes = IdSizes(-1,-1,-1,-1,-1)

    def bodyFromWire(body: IndexedSeqView[Byte])(using idSizes: IdSizes) : IdSizes =
        val checkedReader = CheckedReader(body)
        IdSizes(
           fieldIDSize = checkedReader.read_int32(),
            methodIDSize = checkedReader.read_int32(),
            objectIDSize = checkedReader.read_int32(),
            referenceTypeIDSize = checkedReader.read_int32(),
            frameIDSize = checkedReader.read_int32(),
        )
}
