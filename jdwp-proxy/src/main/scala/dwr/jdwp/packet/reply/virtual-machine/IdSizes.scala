package dwr.jdwp.packet.reply.virtual_machine

import dwr.reader._
import dwr.jdwp.packet._

class IdSizes(
    val fieldIDSize: Int,
    val methodIDSize: Int,
    val objectIDSize: Int,
    val referenceTypeIDSize: Int,
    val frameIDSize: Int
) {}

object IdSizes extends FromWire[IdSizes] {
    /**
     * During VM connect, we don't know IdSizes, so we ask for it,
     * but we need an IdSizes to to call `fromWire` to parse the IdSizes reply packet.
     * This is a placeholder for that situation.
     */
    def dummy : IdSizes = IdSizes(-1,-1,-1,-1,-1)

    def fromWire(idSizes: IdSizes, body: Array[Byte]) : IdSizes =
        val checkedReader = CheckedReader(body)
        IdSizes(
           fieldIDSize = checkedReader.read_int32(),
            methodIDSize = checkedReader.read_int32(),
            objectIDSize = checkedReader.read_int32(),
            referenceTypeIDSize = checkedReader.read_int32(),
            frameIDSize = checkedReader.read_int32(),
        )
}
