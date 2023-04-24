package dwr.jdwp.packet

import dwr.jdwp.packet.raw

enum Command(val commandSetID: Byte, val commandID: Byte):
  case VirtualMachine_Dispose extends Command(Command.VIRTUAL_MACHINE, 6)
  case VirtualMachine_IDSizes extends Command(Command.VIRTUAL_MACHINE, 7)
  case VirtualMachine_Suspend extends Command(Command.VIRTUAL_MACHINE, 8)
  case VirtualMachine_Resume extends Command(Command.VIRTUAL_MACHINE, 9)

  case ObjectReference_DisableCollection extends Command(Command.OBJECT_REFERENCE, 7)
  case ObjectReference_EnableCollection extends Command(Command.OBJECT_REFERENCE, 8)

  case EventRequest_Set extends Command(Command.EVENT_REQUEST, 1)
  case EventRequest_Clear extends Command(Command.EVENT_REQUEST, 2)
  case EventRequest_ClearAllBreakpoints extends Command(Command.EVENT_REQUEST, 3)

  case ThreadReference_Suspend extends Command(Command.THREAD_REFERENCE, 2)
  case ThreadReference_Resume extends Command(Command.THREAD_REFERENCE, 3)

  case Event_Composite extends Command(Command.EVENT, 100)

object Command {
  import scala.collection.immutable.HashMap
  private type CommandParser = BodyFromWire[JdwpCommand]

  def maybeGetParser(commandSet: Byte, command: Byte) : Option[CommandParser] =
    parsersByCmdByCmdSet
      .get(commandSet)
      .flatMap(parsersByCmd => parsersByCmd.get(command))

  def maybeGetParser(cmd: Command) : Option[CommandParser] =
    maybeGetParser(cmd.commandSetID, cmd.commandID)

  def maybeGetParser(packet: raw.Command) : Option[CommandParser] =
    maybeGetParser(packet.commandSet, packet.command)

  //
  // command set IDs
  //
  final val VIRTUAL_MACHINE : Byte = 1
  final val OBJECT_REFERENCE : Byte = 9
  final val THREAD_REFERENCE : Byte = 11
  final val EVENT_REQUEST : Byte = 15
  final val EVENT : Byte = 64

  import scala.collection.immutable
  private final val parsersByCmdByCmdSet = immutable.HashMap[Byte, immutable.HashMap[Byte, CommandParser]](
    (
      VIRTUAL_MACHINE,
      immutable.HashMap(
        (VirtualMachine_Dispose.commandID, command.virtual_machine.Dispose),
        (VirtualMachine_IDSizes.commandID, command.virtual_machine.IdSizes),
        (VirtualMachine_Suspend.commandID, command.virtual_machine.Suspend),
        (VirtualMachine_Resume.commandID, command.virtual_machine.Resume),
      )
    ),
    (
      OBJECT_REFERENCE,
      immutable.HashMap(
        (ObjectReference_DisableCollection.commandID, command.object_reference.DisableCollection),
        (ObjectReference_EnableCollection.commandID, command.object_reference.EnableCollection)
      )
    ),
    (
      THREAD_REFERENCE,
      immutable.HashMap(
        (ThreadReference_Suspend.commandID, command.thread_reference.Suspend),
        (ThreadReference_Resume.commandID, command.thread_reference.Resume),
      )
    ),
    (
      EVENT_REQUEST,
      immutable.HashMap(
        (EventRequest_Set.commandID, command.event_request.Set)
      )
    ),
    (
      EVENT,
      immutable.HashMap(
        (Event_Composite.commandID, command.event.Composite)
      )
    ),
  )
}
