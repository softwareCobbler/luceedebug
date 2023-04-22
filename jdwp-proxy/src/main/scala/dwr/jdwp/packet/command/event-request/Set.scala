package dwr.jdwp.packet.command.event_request

import dwr.reader._

import dwr.jdwp._
import dwr.jdwp.packet._

class Set(
    val eventKind: Byte,
    val suspendPolicy: Byte,
    val modifiers: Seq[Set.Modifier]
) extends JdwpCommand {
    val command = Command.EventRequest_Set
    def hasCount() = modifiers.exists(_ match
        // what if some client sends zero or a negative value?
        case Set.Modifier.Count(c) if c > 0 => true
        case _ => false
    )
}

object Set extends BodyFromWire[Set] {
    enum Modifier:
        case Count(count: Int)
        case Conditional(exprID: Int)
        case ThreadOnly(thread: Long)
        case ClassOnly(clazz: Long)
        case ClassMatch(classPattern: String)
        case ClassExclude(classPattern: String)
        case LocationOnly(loc: Location)
        case ExceptionOnly(exceptionOrNull: Long, caught: Boolean, uncaught: Boolean)
        case FieldOnly(declaringClazz: Long, fieldID: Long)
        case Step(thread: Long, size: Int, depth: Int)
        case InstanceOnly(instance: Long)
        case SourceNameMatch(sourceNamePattern: String)

    def bodyFromWire(idSizes: IdSizes, buffer: Array[Byte]): Set =
        import Modifier._
        import EventRequestModifier._
        val reader = JdwpSizedReader(idSizes, buffer)
        val eventKind = reader.read_int8()
        val suspendPolicy = reader.read_int8()
        val modifiersCount = reader.read_int32()
        val modifiers = (0 until modifiersCount).map(_ => {
        val modKind = reader.read_int8()
        modKind match
            case COUNT => Count(reader.read_int32())
            case CONDITIONAL => Conditional(reader.read_int32())
            case THREAD_ONLY => ThreadOnly(reader.readThreadID())
            case CLASS_ONLY => ClassOnly(reader.readReferenceTypeID())
            case CLASS_MATCH => ClassMatch(reader.readString())
            case CLASS_EXCLUDE => ClassExclude(reader.readString())
            case LOCATION_ONLY => LocationOnly(reader.readLocation())
            case EXCEPTION_ONLY => ExceptionOnly(reader.readReferenceTypeID(), reader.readBoolean(), reader.readBoolean())
            case FIELD_ONLY => FieldOnly(reader.readReferenceTypeID(), reader.readFieldID())
            case STEP => Step(reader.readThreadID(), reader.read_int32(), reader.read_int32())
            case INSTANCE_ONLY => InstanceOnly(reader.readObjectID())
            case SOURCE_NAME_MATCH => SourceNameMatch(reader.readString())
            case _ => throw new RuntimeException(s"Unexpected modkind '${modKind}'")
        })
        Set(eventKind, suspendPolicy, modifiers)
}
