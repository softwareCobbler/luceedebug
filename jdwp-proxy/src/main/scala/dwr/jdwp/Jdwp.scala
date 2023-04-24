package dwr.jdwp

import dwr.utils.ByteWrangler
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable

import dwr.jdwp.packet.IdSizes
import java.nio.charset.StandardCharsets

trait WriteableJdwpEntity extends Any {
  def toBuffer(buffer: ArrayBuffer[Byte])(using idSizes: IdSizes) : Unit
}

implicit class BoolOps(val v: Boolean) extends AnyVal with WriteableJdwpEntity {
  def toBuffer(buffer: ArrayBuffer[Byte])(using idSizes: IdSizes) : Unit =
    buffer += (if v then 1 else 0).asInstanceOf[Byte]
}

implicit class ByteOps(val v: Byte) extends AnyVal with WriteableJdwpEntity {
  def toBuffer(buffer: ArrayBuffer[Byte])(using idSizes: IdSizes) : Unit =
    buffer += v
}

implicit class ShortOps(val v: Short) extends AnyVal with WriteableJdwpEntity {
  def toBuffer(buffer: ArrayBuffer[Byte])(using idSizes: IdSizes) : Unit =
    buffer.addAll(ByteWrangler.int16_to_beI16(v))
}

implicit class IntOps(val v: Int) extends AnyVal with WriteableJdwpEntity {
  def toBuffer(buffer: ArrayBuffer[Byte])(using idSizes: IdSizes) : Unit =
    buffer.addAll(ByteWrangler.int32_to_beI32(v))
}

implicit class LongOps(val v: Long) extends AnyVal with WriteableJdwpEntity {
  def toBuffer(buffer: ArrayBuffer[Byte])(using idSizes: IdSizes) : Unit =
    buffer.addAll(ByteWrangler.int64_to_beI64(v))
}

implicit class StringOps(val v: String) extends AnyVal with WriteableJdwpEntity {
  def toBuffer(buffer: ArrayBuffer[Byte])(using idSizes: IdSizes) : Unit =
    buffer.addAll(ByteWrangler.int32_to_beI32(v.length()))
    buffer.addAll(v.getBytes(StandardCharsets.UTF_8)) // see docs on "modified utf8", this is probably wrong in some cases
}

final case class Location(val typeTag: Byte, val classID: Long, val methodID: Long, val index: Long) extends WriteableJdwpEntity {
  def toBuffer(buffer: ArrayBuffer[Byte])(using idSizes: IdSizes) : Unit =
    typeTag.toBuffer(buffer)
    classID.toBuffer(buffer)
    methodID.toBuffer(buffer)
    index.toBuffer(buffer)
}

final class TaggedObjectID(tag: Byte, value: ObjectID) extends WriteableJdwpEntity {
  def toBuffer(buffer: ArrayBuffer[Byte])(using idSizes: IdSizes) : Unit =
    tag.toBuffer(buffer)
    value.toBuffer(buffer)
}

final class Value(tag: Byte, value: Long) extends WriteableJdwpEntity {
  def toBuffer(buffer: ArrayBuffer[Byte])(using idSizes: IdSizes) : Unit =
    tag.toBuffer(buffer)
    value.toBuffer(buffer)
}

final class ObjectID(value: Long) extends AnyVal with WriteableJdwpEntity {
  def toBuffer(buffer: ArrayBuffer[Byte])(using idSizes: IdSizes) : Unit = writeObjectID(value, buffer)
  def asLong : Long = value
}

private inline def write4Or8(size: Int, v: Long, buffer: ArrayBuffer[Byte]): Unit =
  size match
    case 4 => buffer.addAll(ByteWrangler.int32_to_beI32((v & 0xFFFFFFFF).asInstanceOf[Int]))
    case 8 => buffer.addAll(ByteWrangler.int64_to_beI64(v))
    case _ => throw new RuntimeException(s"Expected 4 or 8 as size param, but got ${size}")
  
private inline def writeFieldID(v: Long, buffer: ArrayBuffer[Byte])(using idSizes: IdSizes): Unit = write4Or8(idSizes.fieldIDSize, v, buffer)
private inline def writeFrameID(v: Long, buffer: ArrayBuffer[Byte])(using idSizes: IdSizes): Unit = write4Or8(idSizes.frameIDSize, v, buffer)
private inline def writeMethodID(v: Long, buffer: ArrayBuffer[Byte])(using idSizes: IdSizes): Unit = write4Or8(idSizes.methodIDSize, v, buffer)
private inline def writeObjectID(v: Long, buffer: ArrayBuffer[Byte])(using idSizes: IdSizes): Unit = write4Or8(idSizes.objectIDSize, v, buffer)
private inline def writeReferenceTypeID(v: Long, buffer: ArrayBuffer[Byte])(using idSizes: IdSizes): Unit = write4Or8(idSizes.referenceTypeIDSize, v, buffer)

class ThreadID(val threadID: Long) extends AnyVal with WriteableJdwpEntity {
  def toBuffer(buffer: mutable.ArrayBuffer[Byte])(using idSizes: IdSizes): Unit = writeObjectID(threadID, buffer)
}

object EventKind {
  final val SINGLE_STEP                   : Byte = 1
  final val BREAKPOINT                    : Byte = 2
  final val FRAME_POP                     : Byte = 3
  final val EXCEPTION                     : Byte = 4
  final val USER_DEFINED                  : Byte = 5
  final val THREAD_START                  : Byte = 6
  final val THREAD_DEATH                  : Byte = 7
  final val THREAD_END                    : Byte = 7 // obsolete - was used in jvmdi
  final val CLASS_PREPARE                 : Byte = 8
  final val CLASS_UNLOAD                  : Byte = 9
  final val CLASS_LOAD                    : Byte = 10
  final val FIELD_ACCESS                  : Byte = 20
  final val FIELD_MODIFICATION            : Byte = 21
  final val EXCEPTION_CATCH               : Byte = 30
  final val METHOD_ENTRY                  : Byte = 40
  final val METHOD_EXIT                   : Byte = 41
  final val METHOD_EXIT_WITH_RETURN_VALUE : Byte = 42
  final val MONITOR_CONTENDED_ENTER       : Byte = 43
  final val MONITOR_CONTENDED_ENTERED     : Byte = 44
  final val MONITOR_WAIT                  : Byte = 45
  final val MONITOR_WAITED                : Byte = 46
  final val VM_START                      : Byte = 90
  final val VM_INIT                       : Byte = 90 // obsolete - was used in jvmdi
  final val VM_DEATH                      : Byte = 99
  final val VM_DISCONNECTED               : Byte = 100 // Never sent across JDWP
}

object TypeTag {
  final val CLASS     : Byte = 1
  final val INTERFACE : Byte = 2
  final val ARRAY     : Byte = 3
}

object Tag {
  final val ARRAY        : Byte = '['
  final val BYTE         : Byte = 'B'
  final val CHAR         : Byte = 'C'
  final val OBJECT       : Byte = 'L'
  final val FLOAT        : Byte = 'F'
  final val DOUBLE       : Byte = 'D'
  final val INT          : Byte = 'I'
  final val LONG         : Byte = 'J'
  final val SHORT        : Byte = 'S'
  final val VOID         : Byte = 'V'
  final val BOOLEAN      : Byte = 'Z'
  final val STRING       : Byte = 's'
  final val THREAD       : Byte = 't'
  final val THREAD_GROUP : Byte = 'g'
  final val CLASS_LOADER : Byte = 'l'
  final val CLASS_OBJECT : Byte = 'c'
}

object EventRequestModifier {
  final val COUNT : Byte = 1
  final val CONDITIONAL : Byte = 2
  final val THREAD_ONLY : Byte = 3
  final val CLASS_ONLY : Byte = 4
  final val CLASS_MATCH : Byte = 5
  final val CLASS_EXCLUDE : Byte = 6
  final val LOCATION_ONLY : Byte = 7
  final val EXCEPTION_ONLY : Byte = 8
  final val FIELD_ONLY : Byte = 9
  final val STEP : Byte = 10
  final val INSTANCE_ONLY : Byte = 11
  final val SOURCE_NAME_MATCH : Byte = 12
}

object SuspendPolicy {
  final val NONE : Byte = 0
  final val EVENT_THREAD : Byte = 1
  final val ALL : Byte = 2
}
