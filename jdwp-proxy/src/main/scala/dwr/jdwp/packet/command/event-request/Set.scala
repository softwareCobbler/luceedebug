package dwr.jdwp.packet.command.event_request

import dwr.reader._

import dwr.jdwp._
import dwr.jdwp.packet._
import scala.collection.IndexedSeqView
import scala.collection.mutable.ArrayBuffer

class Set(
    val eventKind: Byte,
    val suspendPolicy: Byte,
    val modifiers: Seq[Set.Modifier]
) extends JdwpCommand with BodyToWire {
    val command = Command.EventRequest_Set
    def hasCount() = modifiers.exists(_ match
        // what if some client sends zero or a negative value?
        case Set.Modifier.Count(c) if c > 0 => true
        case _ => false
    )

    def bodyToWire()(using idSizes: IdSizes) : Array[Byte] =
        val buffer = new ArrayBuffer[Byte]()
        eventKind.toBuffer(buffer)
        suspendPolicy.toBuffer(buffer)
        modifiers.size.toBuffer(buffer)
        modifiers.foreach(_.toBuffer(buffer))
        buffer.toArray
}

object Set extends BodyFromWire[Set] {
    enum Modifier extends WriteableJdwpEntity:
        case Count(count: Int)
        case Conditional(exprID: Int)
        case ThreadOnly(thread: ThreadID)
        case ClassOnly(clazz: Long)
        case ClassMatch(classPattern: String)
        case ClassExclude(classPattern: String)
        case LocationOnly(loc: Location)
        case ExceptionOnly(exceptionOrNull: Long, caught: Boolean, uncaught: Boolean)
        case FieldOnly(declaringClazz: Long, fieldID: Long)
        case Step(thread: ThreadID, size: Int, depth: Int)
        case InstanceOnly(instance: ObjectID)
        case SourceNameMatch(sourceNamePattern: String)
        def toBuffer(buffer: ArrayBuffer[Byte])(using idSizes: IdSizes) : Unit =
            import EventRequestModifier._
            this match
                case Count(count) =>
                    COUNT.toBuffer(buffer)
                    count.toBuffer(buffer)
                case Conditional(exprID) =>
                    CONDITIONAL.toBuffer(buffer)
                    exprID.toBuffer(buffer)
                case ThreadOnly(threadID) =>
                    THREAD_ONLY.toBuffer(buffer)
                    threadID.toBuffer(buffer)
                case ClassOnly(clazz) =>
                    CLASS_ONLY.toBuffer(buffer)
                    clazz.toBuffer(buffer)
                case ClassMatch(classPattern) =>
                    CLASS_MATCH.toBuffer(buffer)
                    classPattern.toBuffer(buffer)
                case ClassExclude(classPattern) =>
                    CLASS_EXCLUDE.toBuffer(buffer)
                    classPattern.toBuffer(buffer)
                case LocationOnly(loc: Location) =>
                    LOCATION_ONLY.toBuffer(buffer)
                    loc.toBuffer(buffer)
                case ExceptionOnly(exceptionOrNull, caught, uncaught) =>
                    EXCEPTION_ONLY.toBuffer(buffer)
                    exceptionOrNull.toBuffer(buffer)
                    caught.toBuffer(buffer)
                    uncaught.toBuffer(buffer)
                case FieldOnly(declaringClass, fieldID) =>
                    FIELD_ONLY.toBuffer(buffer)
                    declaringClass.toBuffer(buffer)
                    fieldID.toBuffer(buffer)
                case Step(threadID, size, depth) =>
                    STEP.toBuffer(buffer)
                    threadID.toBuffer(buffer)
                    size.toBuffer(buffer)
                    depth.toBuffer(buffer)
                case InstanceOnly(instance) =>
                    INSTANCE_ONLY.toBuffer(buffer)
                    instance.toBuffer(buffer)
                case SourceNameMatch(sourceNamePattern) =>
                    SOURCE_NAME_MATCH.toBuffer(buffer)
                    sourceNamePattern.toBuffer(buffer)

    def bodyFromWire(buffer: IndexedSeqView[Byte])(using idSizes: IdSizes): Set =
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
