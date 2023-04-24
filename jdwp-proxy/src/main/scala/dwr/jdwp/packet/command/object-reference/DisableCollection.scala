package dwr.jdwp.packet.command.object_reference

import dwr.jdwp._
import dwr.jdwp.packet._
import dwr.jdwp.packet.reply
import dwr.reader.JdwpSizedReader
import scala.collection.IndexedSeqView
import scala.collection.mutable.ArrayBuffer

class DisableCollection(val objectID: ObjectID) extends JdwpCommand with BodyToWire {
    final val command = Command.ObjectReference_DisableCollection
    def bodyToWire()(using idSizes: reply.virtual_machine.IdSizes) : Array[Byte] =
        val buffer = new ArrayBuffer[Byte]()
        objectID.toBuffer(buffer)
        buffer.toArray
}

object DisableCollection extends BodyFromWire[DisableCollection] {
    def bodyFromWire(body: IndexedSeqView[Byte])(using idSizes: reply.virtual_machine.IdSizes): DisableCollection =
        val reader = JdwpSizedReader(idSizes, body)
        DisableCollection(
            objectID = reader.readObjectID()
        )
}
