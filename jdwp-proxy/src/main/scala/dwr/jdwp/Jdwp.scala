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

final class Location(val typeTag: Byte, val classID: Long, val methodID: Long, val index: Long) extends WriteableJdwpEntity {
  def toBuffer(buffer: ArrayBuffer[Byte])(using idSizes: IdSizes) : Unit =
    typeTag.toBuffer(buffer)
    classID.toBuffer(buffer)
    methodID.toBuffer(buffer)
    index.toBuffer(buffer)
}

final class TaggedObjectID(tag: Byte, value: Long) extends WriteableJdwpEntity {
  def toBuffer(buffer: ArrayBuffer[Byte])(using idSizes: IdSizes) : Unit =
    tag.toBuffer(buffer)
    value.toBuffer(buffer)
}

final class Value(tag: Byte, value: Long) extends WriteableJdwpEntity {
  def toBuffer(buffer: ArrayBuffer[Byte])(using idSizes: IdSizes) : Unit =
    tag.toBuffer(buffer)
    value.toBuffer(buffer)
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
  final val SINGLE_STEP                   = 1
  final val BREAKPOINT                    = 2
  final val FRAME_POP                     = 3
  final val EXCEPTION                     = 4
  final val USER_DEFINED                  = 5
  final val THREAD_START                  = 6
  final val THREAD_DEATH                  = 7
  final val THREAD_END                    = 7 // obsolete - was used in jvmdi
  final val CLASS_PREPARE                 = 8
  final val CLASS_UNLOAD                  = 9
  final val CLASS_LOAD                    = 10
  final val FIELD_ACCESS                  = 20
  final val FIELD_MODIFICATION            = 21
  final val EXCEPTION_CATCH               = 30
  final val METHOD_ENTRY                  = 40
  final val METHOD_EXIT                   = 41
  final val METHOD_EXIT_WITH_RETURN_VALUE = 42
  final val MONITOR_CONTENDED_ENTER       = 43
  final val MONITOR_CONTENDED_ENTERED     = 44
  final val MONITOR_WAIT                  = 45
  final val MONITOR_WAITED                = 46
  final val VM_START                      = 90
  final val VM_INIT                       = 90 // obsolete - was used in jvmdi
  final val VM_DEATH                      = 99
  final val VM_DISCONNECTED               = 100 // Never sent across JDWP
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
