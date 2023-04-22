package dwr.jdwp.packet

import dwr.utils._
import dwr.reader._

import dwr.jdwp.packet.reply
import dwr.jdwp.packet.command
import dwr.raw_packet as raw

export dwr.jdwp.packet.reply.virtual_machine.IdSizes

class JdwpPacket {}
class JdwpReplyHeader(val length: Int, val id: Int, val flags: Byte, val errorCode: Short)

trait JdwpCommand {
  val command: Command
}

enum Command(val commandSetID: Byte, val commandID: Byte):
  case VirtualMachine_IDSizes extends Command(Command.VIRTUAL_MACHINE, 7)
  
  case EventRequest_Set extends Command(Command.EVENT_REQUEST, 1)
  case EventRequest_Clear extends Command(Command.EVENT_REQUEST, 2)
  case EventRequest_ClearAllBreakpoints extends Command(Command.EVENT_REQUEST, 3)
  
  case Event_Composite extends Command(Command.EVENT, 100)

object Command {
  import scala.collection.immutable.HashMap
  type CommandParser = BodyFromWire[JdwpCommand]

  def maybeGetParser(commandSet: Byte, command: Byte) : Option[CommandParser] =
    parsersByCmdByCmdSet
      .get(commandSet)
      .flatMap(parsersByCmd => parsersByCmd.get(command))

  def maybeGetParser(cmd: Command) : Option[CommandParser] =
    maybeGetParser(cmd.commandSetID, cmd.commandID)

  def maybeGetParser(packet: raw.Command) : Option[CommandParser] =
    maybeGetParser(packet.commandSet, packet.command)

  final val VIRTUAL_MACHINE : Byte = 1
  final val EVENT_REQUEST : Byte = 15
  final val EVENT : Byte = 64
  final val parsersByCmdByCmdSet : HashMap[Byte, HashMap[Byte, CommandParser]] = HashMap(
    (
      VIRTUAL_MACHINE,
      HashMap(
        (VirtualMachine_IDSizes.commandID, command.virtual_machine.IdSizes)
      )
    ),
    (
      EVENT_REQUEST,
      HashMap(
        (EventRequest_Set.commandID, command.event_request.Set)
      )
    ),
    (
      EVENT,
      HashMap(
        (Event_Composite.commandID, command.event.Composite)
      )
    )
  )
}

object CommandPacket {
  def toWire(id: Int, command: JdwpCommand & BodyToWire) : Array[Byte] =
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
  def toWire(id: Int, reply: BodyToWire) : Array[Byte] =
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
  def bodyToWire() : Array[Byte]
}

trait BodyFromWire[+T] {
  def bodyFromWire(idSizes: IdSizes, buffer: Array[Byte]): T // TODO: should be Option[T]
}
