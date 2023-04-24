package dwr.jdwp.packet

import dwr.utils._
import dwr.reader._

import dwr.jdwp.packet.reply
import dwr.jdwp.packet.command
import scala.collection.IndexedSeqView

export dwr.jdwp.packet.reply.virtual_machine.IdSizes

trait JdwpCommand {
  val command: Command
}

trait JdwpReply {}

object CommandPacket {
  def toWire(id: Int, command: JdwpCommand & BodyToWire)(using idSizes: IdSizes): Array[Byte] =
    val body = command.bodyToWire()
    val b_id = ByteWrangler.int32_to_beI32(id);
    val b_length = ByteWrangler.int32_to_beI32(body.length + 11);
    val commandSetID = command.command.commandSetID
    val commandID = command.command.commandID
    val header = Array[Byte](
      b_length(0),
      b_length(1),
      b_length(2),
      b_length(3),
      b_id(0),
      b_id(1),
      b_id(2),
      b_id(3),
      0,
      commandSetID,
      commandID
    )
    header ++ body
}

object ReplyPacket {
  def toWire(id: Int, reply: JdwpReply & BodyToWire)(using idSizes: IdSizes) : Array[Byte] =
    val body = reply.bodyToWire()
    val b_length = ByteWrangler.int32_to_beI32(body.length + 11);
    val b_id = ByteWrangler.int32_to_beI32(id);
    val flags : Byte = 0x80.asInstanceOf[Byte]
    val b_errorCode = ByteWrangler.int16_to_beI16(0)
    val header = Array[Byte](
      b_length(0),
      b_length(1),
      b_length(2),
      b_length(3),
      b_id(0),
      b_id(1),
      b_id(2),
      b_id(3),
      flags,
      b_errorCode(0),
      b_errorCode(1)
    )
    header ++ body
}

trait BodyToWire {
  def bodyToWire()(using idSizes: IdSizes) : Array[Byte]
}

trait BodyFromWire[+T] {
  def bodyFromWire(buffer: IndexedSeqView[Byte])(using idSizes: IdSizes): T // TODO: should be Option[T]
}
