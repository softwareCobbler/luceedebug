package dwr.jdwp.packet.command.event

import dwr.jdwp._
import dwr.jdwp.packet._

import dwr.reader._
import scala.collection.mutable.ArrayBuffer
import dwr.utils.ByteWrangler
import scala.collection.mutable
import scala.collection.IndexedSeqView

enum Event(val requestID: Int) extends WriteableJdwpEntity:
    case VMStart(requestID_ : Int, thread: ThreadID)
        extends Event(requestID_)
    case VMDeath(requestID_ : Int)
        extends Event(requestID_)
    case SingleStep(requestID_ : Int, thread: ThreadID, location: Location)
        extends Event(requestID_)
    case Breakpoint(requestID_ : Int, thread: ThreadID, location: Location)
        extends Event(requestID_)
    case MethodEntry(requestID_ : Int, thread: ThreadID, location: Location)
        extends Event(requestID_)
    case MethodExit(requestID_ : Int, thread: ThreadID, location: Location)
        extends Event(requestID_)
    case MethodExitWithReturnValue(requestID_ : Int, thread: ThreadID, location: Location, value: Value)
        extends Event(requestID_)
    case MonitorContendedEnter(requestID_ : Int, thread: ThreadID, obj: TaggedObjectID, location: Location)
        extends Event(requestID_)
    case MonitorContendedEntered(requestID_ : Int, thread: ThreadID, obj: TaggedObjectID, location: Location)
        extends Event(requestID_)
    case MonitorWait(requestID_ : Int, thread: ThreadID, obj: TaggedObjectID, location: Location, timeout: Long)
        extends Event(requestID_)
    case MonitorWaited(requestID_ : Int, thread: ThreadID, obj: TaggedObjectID, location: Location, timed_out: Boolean)
        extends Event(requestID_)
    case Exception(requestID_ : Int, thread: ThreadID, location: Location, exception: TaggedObjectID, catchLocation: Location)
        extends Event(requestID_)
    case ThreadStart(requestID_ : Int, thread: ThreadID)
        extends Event(requestID_)
    case ThreadDeath(requestID_ : Int, thread: ThreadID)
        extends Event(requestID_)
    case ClassPrepare(requestID_ : Int, thread: ThreadID, refTypeTag: Byte, refTypeID: Long, signature: String, status: Int)
        extends Event(requestID_)
    case ClassUnload(requestID_ : Int, signature: String)
        extends Event(requestID_)
    case FieldAccess(requestID_ : Int, thread: ThreadID, location: Location, refTypeTag: Byte, refTypeID: Long, fieldID: Long, obj: TaggedObjectID)
        extends Event(requestID_)
    case FieldModification(requestID_ : Int, thread: ThreadID, location: Location, refTypeTag: Byte, refTypeID: Long, fieldID: Long, obj: TaggedObjectID, valueToBe: Value)
        extends Event(requestID_)

    def getThreadID() : Option[ThreadID] =
        this match
            case VMStart(_, thread) => Some(thread)
            case VMDeath(_) => None
            case SingleStep(_, thread, _) => Some(thread)
            case Breakpoint(_, thread, _) => Some(thread)
            case MethodEntry(_, thread, _) => Some(thread)
            case MethodExit(_, thread, _) => Some(thread)
            case MethodExitWithReturnValue(_, thread, _, _) => Some(thread)
            case MonitorContendedEnter(_, thread, _, _) => Some(thread)
            case MonitorContendedEntered(_, thread, _, _) => Some(thread)
            case MonitorWait(_, thread, _, _, _) => Some(thread)
            case MonitorWaited(_, thread, _, _, _) => Some(thread)
            case Exception(_, thread, _, _, _) => Some(thread)
            case ThreadStart(_, thread) => Some(thread)
            case ThreadDeath(_, thread) => Some(thread)
            case ClassPrepare(_, thread, _, _, _, _) => Some(thread)
            case ClassUnload(_, _) => None
            case FieldAccess(_, thread, _, _, _, _, _) => Some(thread)
            case FieldModification(_, thread, _, _, _, _, _, _) => Some(thread)

    def toBuffer(buffer: ArrayBuffer[Byte])(using idSizes: IdSizes) : Unit =
        this match
            case VMStart(requestID_, thread) =>
                EventKind.VM_START.toBuffer(buffer)
                requestID_.toBuffer(buffer)
                thread.toBuffer(buffer)
            case VMDeath(requestID_) =>
                EventKind.VM_DEATH.toBuffer(buffer)
                requestID_.toBuffer(buffer)
            case SingleStep(requestID_, thread, location) =>
                EventKind.SINGLE_STEP.toBuffer(buffer)
                requestID_.toBuffer(buffer)
                thread.toBuffer(buffer)
                location.toBuffer(buffer)
            case Breakpoint(requestID_, thread, location) =>
                EventKind.BREAKPOINT.toBuffer(buffer)
                requestID_.toBuffer(buffer)
                thread.toBuffer(buffer)
                location.toBuffer(buffer)
            case MethodEntry(requestID_, thread, location) =>
                EventKind.METHOD_ENTRY.toBuffer(buffer)
                requestID_.toBuffer(buffer)
                thread.toBuffer(buffer)
                location.toBuffer(buffer)
            case MethodExit(requestID_, thread, location) =>
                EventKind.METHOD_EXIT.toBuffer(buffer)
                requestID_.toBuffer(buffer)
                thread.toBuffer(buffer)
                location.toBuffer(buffer)
            case MethodExitWithReturnValue(requestID_, thread, location, value) =>
                EventKind.METHOD_EXIT_WITH_RETURN_VALUE.toBuffer(buffer)
                requestID_.toBuffer(buffer)
                thread.toBuffer(buffer)
                location.toBuffer(buffer)
                value.toBuffer(buffer)
            case MonitorContendedEnter(requestID_, thread, obj, location) =>
                EventKind.MONITOR_CONTENDED_ENTER.toBuffer(buffer)
                requestID_.toBuffer(buffer)
                thread.toBuffer(buffer)
                obj.toBuffer(buffer)
                location.toBuffer(buffer)
            case MonitorContendedEntered(requestID_, thread, obj, location) =>
                EventKind.MONITOR_CONTENDED_ENTERED.toBuffer(buffer)
                requestID_.toBuffer(buffer)
                thread.toBuffer(buffer)
                obj.toBuffer(buffer)
                location.toBuffer(buffer)
            case MonitorWait(requestID_, thread, obj, location, timeout) =>
                EventKind.MONITOR_WAIT.toBuffer(buffer)
                requestID_.toBuffer(buffer)
                thread.toBuffer(buffer)
                obj.toBuffer(buffer)
                location.toBuffer(buffer)
                timeout.toBuffer(buffer)
            case MonitorWaited(requestID_, thread, obj, location, timed_out) =>
                EventKind.MONITOR_WAITED.toBuffer(buffer)
                requestID_.toBuffer(buffer)
                thread.toBuffer(buffer)
                obj.toBuffer(buffer)
                location.toBuffer(buffer)
                timed_out.toBuffer(buffer)
            case Exception(requestID_, thread, location, exception, catchLocation) =>
                EventKind.EXCEPTION.toBuffer(buffer)
                requestID_.toBuffer(buffer)
                thread.toBuffer(buffer)
                exception.toBuffer(buffer)
                catchLocation.toBuffer(buffer)
            case ThreadStart(requestID_, thread) =>
                EventKind.THREAD_START.toBuffer(buffer)
                requestID_.toBuffer(buffer)
                thread.toBuffer(buffer)
            case ThreadDeath(requestID_, thread) =>
                EventKind.THREAD_DEATH.toBuffer(buffer)
                requestID_.toBuffer(buffer)
                thread.toBuffer(buffer)
            case ClassPrepare(requestID_, thread, refTypeTag, refTypeID, signature, status) =>
                EventKind.CLASS_PREPARE.toBuffer(buffer)
                requestID_.toBuffer(buffer)
                thread.toBuffer(buffer)
                refTypeTag.toBuffer(buffer)
                refTypeID.toBuffer(buffer)
                signature.toBuffer(buffer)
                status.toBuffer(buffer)
            case ClassUnload(requestID_, signature) =>
                EventKind.CLASS_UNLOAD.toBuffer(buffer)
                requestID_.toBuffer(buffer)
                signature.toBuffer(buffer)
            case FieldAccess(requestID_, thread, location, refTypeTag, refTypeID, fieldID, obj) =>
                EventKind.FIELD_ACCESS.toBuffer(buffer)
                requestID_.toBuffer(buffer)
                thread.toBuffer(buffer)
                location.toBuffer(buffer)
                refTypeTag.toBuffer(buffer)
                refTypeID.toBuffer(buffer)
                fieldID.toBuffer(buffer)
                obj.toBuffer(buffer)
            case FieldModification(requestID_, thread, location, refTypeTag, refTypeID, fieldID, obj, valueToBe) =>
                EventKind.FIELD_MODIFICATION.toBuffer(buffer)
                requestID_.toBuffer(buffer)
                thread.toBuffer(buffer)
                location.toBuffer(buffer)
                refTypeTag.toBuffer(buffer)
                refTypeID.toBuffer(buffer)
                fieldID.toBuffer(buffer)
                obj.toBuffer(buffer)
                valueToBe.toBuffer(buffer)

class Composite(
    val suspendPolicy: Byte,
    val events: Seq[Event]
) extends JdwpCommand with BodyToWire {
    val command: Command = Command.Event_Composite

    def bodyToWire()(using idSizes: IdSizes): Array[Byte] =
        val buffer = new ArrayBuffer[Byte](32)
        buffer += suspendPolicy
        buffer.addAll(ByteWrangler.int32_to_beI32(events.length))
        for (event <- events) do
            event.toBuffer(buffer)
        buffer.toArray
}

object Composite extends BodyFromWire[Composite] {
    def bodyFromWire(buffer: IndexedSeqView[Byte])(using idSizes: IdSizes): Composite =
        import Event._
        import EventKind._
        val reader = JdwpSizedReader(idSizes, buffer)
        val suspendPolicy = reader.read_int8()
        val eventCount = reader.read_int32()
        val events = (0 until eventCount).map(i => {
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
