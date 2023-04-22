package dwr.jdwp.packet.command.event

import dwr.jdwp._
import dwr.jdwp.packet._

import dwr.reader._

enum Event(val requestID: Int):
    case VMStart(requestID_ : Int, thread: Long)
        extends Event(requestID_)
    case VMDeath(requestID_ : Int)
        extends Event(requestID_)
    case SingleStep(requestID_ : Int, thread: Long, location: Location)
        extends Event(requestID_)
    case Breakpoint(requestID_ : Int, thread: Long, location: Location)
        extends Event(requestID_)
    case MethodEntry(requestID_ : Int, thread: Long, location: Location)
        extends Event(requestID_)
    case MethodExit(requestID_ : Int, thread: Long, location: Location)
        extends Event(requestID_)
    case MethodExitWithReturnValue(requestID_ : Int, thread: Long, location: Location, value: Value)
        extends Event(requestID_)
    case MonitorContendedEnter(requestID_ : Int, thread: Long, obj: TaggedObjectID, location: Location)
        extends Event(requestID_)
    case MonitorContendedEntered(requestID_ : Int, thread: Long, obj: TaggedObjectID, location: Location)
        extends Event(requestID_)
    case MonitorWait(requestID_ : Int, thread: Long, obj: TaggedObjectID, location: Location, timeout: Long)
        extends Event(requestID_)
    case MonitorWaited(requestID_ : Int, thread: Long, obj: TaggedObjectID, location: Location, timed_out: Boolean)
        extends Event(requestID_)
    case Exception(requestID_ : Int, thread: Long, location: Location, exception: TaggedObjectID, catchLocation: Location)
        extends Event(requestID_)
    case ThreadStart(requestID_ : Int, thread: Long)
        extends Event(requestID_)
    case ThreadDeath(requestID_ : Int, thread: Long)
        extends Event(requestID_)
    case ClassPrepare(requestID_ : Int, thread: Long, refTypeTag: Byte, refTypeID: Long, signature: String, status: Int)
        extends Event(requestID_)
    case ClassUnload(requestID_ : Int, signature: String)
        extends Event(requestID_)
    case FieldAccess(requestID_ : Int, thread: Long, location: Location, refTypeTag: Byte, refTypeID: Long, fieldID: Long, obj: TaggedObjectID)
        extends Event(requestID_)
    case FieldModification(requestID_ : Int, thread: Long, location: Location, refTypeTag: Byte, refTypeID: Long, fieldID: Long, obj: TaggedObjectID, valueToBe: Value)
        extends Event(requestID_)

class Composite(
    val suspendPolicy: Byte,
    val events: Seq[Event]
) extends JdwpCommand {
    val command: Command = Command.Event_Composite
}

object Composite extends BodyFromWire[Composite] {
    def bodyFromWire(idSizes: IdSizes, buffer: Array[Byte]): Composite =
        import Event._
        import EventKind._
        val reader = JdwpSizedReader(idSizes, buffer)
        val suspendPolicy = reader.read_int8()
        val eventCount = reader.read_int32()
        val events = (0 until eventCount).map(_ => {
            val eventKind = reader.read_int8()
            val requestID = reader.read_int32()
            eventKind match
                case VM_START => VMStart(requestID, reader.readThreadID())
                case SINGLE_STEP => SingleStep(requestID, reader.readThreadID(), reader.readLocation())
                case BREAKPOINT => Breakpoint(requestID, reader.readThreadID(), reader.readLocation())
                case METHOD_ENTRY => MethodEntry(requestID, reader.readThreadID(), reader.readLocation())
                case METHOD_EXIT => MethodExit(requestID, reader.readThreadID(), reader.readLocation())
                case METHOD_EXIT_WITH_RETURN_VALUE => MethodExitWithReturnValue(requestID, reader.readThreadID(), reader.readLocation(), reader.readValue())
                case MONITOR_CONTENDED_ENTER => MonitorContendedEnter(requestID, reader.readThreadID(), reader.readTaggedObjectID(), reader.readLocation())
                case MONITOR_WAIT => MonitorWait(requestID, reader.readThreadID(), reader.readTaggedObjectID(), reader.readLocation(), reader.read_int64())
                case MONITOR_WAITED => MonitorWaited(requestID, reader.readThreadID(), reader.readTaggedObjectID(), reader.readLocation(), reader.readBoolean())
                case EXCEPTION => Exception(requestID, reader.readThreadID(), reader.readLocation(), reader.readTaggedObjectID(), reader.readLocation())
                case THREAD_START => ThreadStart(requestID, reader.readThreadID())
                case THREAD_DEATH => ThreadDeath(requestID, reader.readThreadID())
                case CLASS_PREPARE => ClassPrepare(requestID, reader.readThreadID(), reader.read_int8(), reader.readReferenceTypeID(), reader.readString(), reader.read_int32())
                case CLASS_UNLOAD => ClassUnload(requestID, reader.readString())
                case FIELD_ACCESS => FieldAccess(requestID, reader.readThreadID(), reader.readLocation(), reader.read_int8(), reader.readReferenceTypeID(), reader.readFieldID(), reader.readTaggedObjectID())
                case FIELD_MODIFICATION => FieldModification(requestID, reader.readThreadID(), reader.readLocation(), reader.read_int8(), reader.readReferenceTypeID(), reader.readFieldID(), reader.readTaggedObjectID(), reader.readValue())
                case VM_DEATH => VMDeath(requestID)
                case _ => throw new RuntimeException(s"unexpected eventKind '${eventKind}'")
        })
        Composite(suspendPolicy, events)
}
