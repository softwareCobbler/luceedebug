package dwr.reader

import dwr.utils.{ByteWrangler}
import dwr.jdwp.{Tag, TaggedObjectID, Location, Value}
import dwr.jdwp.packet.reply.virtual_machine.{IdSizes}
import dwr.jdwp._
import java.nio.charset.StandardCharsets
import scala.collection.IndexedSeqView
import scala.util.chaining._

/**
 * java is big endian, network is big endian
 */
class CheckedReader(raw: IndexedSeqView[Byte]) {
  private var index : Int = 0

  private inline def getAndAdvance(len: Int) : IndexedSeqView[Byte] =
    val ret = raw.slice(index, index + len)
    index += len
    ret

  inline def readN(n: Int) : IndexedSeqView[Byte] = getAndAdvance(n)
  def read_int8(): Byte = ByteWrangler.beI8_to_int8(readN(1))
  def read_int16(): Short = ByteWrangler.beI16_to_int16(readN(2))
  def read_int32() : Int = ByteWrangler.beI32_to_int32(readN(4))
  def read_int64() : Long = ByteWrangler.beI64_to_int64(readN(8))
}

class JdwpSizedReader(idSizes: IdSizes, raw: IndexedSeqView[Byte]) extends CheckedReader(raw) {
  private def read4Or8(size: Int) : Long =
    size match
      case 4 => read_int32().asInstanceOf[Long]
      case 8 => read_int64()
      case _ => throw new RuntimeException(s"unexpected field size ${idSizes.fieldIDSize}")
  
  def readBoolean() : Boolean = if read_int8() == 0 then false else true

  def readFieldID() = read4Or8(idSizes.fieldIDSize)
  def readMethodID() = read4Or8(idSizes.methodIDSize)
  def readObjectID() = ObjectID(read4Or8(idSizes.objectIDSize))
  def readReferenceTypeID() = read4Or8(idSizes.referenceTypeIDSize)
  def readFrameID() = read4Or8(idSizes.frameIDSize)

  def readThreadID() : ThreadID = ThreadID(readObjectID().asLong) // roundtrip from objectID -> Long -> ThreadID is zero runtime overhead, right?...
  def readThreadGroupID() = readObjectID()
  def readStringID() = readObjectID()
  def readClassLoaderID() = readObjectID()
  def readClassObjectID() = readObjectID()
  def readArrayID() = readObjectID()
  def readTaggedObjectID() =
    val tag = read_int8()
    val objID = readObjectID()
    TaggedObjectID(tag, objID)

  def readClassID() = readReferenceTypeID()
  def readInterfaceID() = readReferenceTypeID()
  def readArrayTypeID() = readReferenceTypeID()

  def readString() : String =
    val len = read_int32()
    val bytes = readN(len)
    String(bytes.toArray, StandardCharsets.UTF_8)

  def readLocation() : Location =
    Location(
      typeTag = read_int8(),
      classID = readClassID(),
      methodID = readMethodID(),
      index = read_int64(),
    )

  def readValue() : Value =
    val tag = read_int8()
    val value = tag match
      case Tag.ARRAY => readObjectID().asLong
      case Tag.BYTE => read_int8().asLong
      case Tag.CHAR => read_int16().asLong
      case Tag.OBJECT => readObjectID().asLong
      case Tag.FLOAT => read_int32().asLong
      case Tag.DOUBLE => read_int64().asLong
      case Tag.INT => read_int32().asLong
      case Tag.LONG => read_int64().asLong
      case Tag.SHORT => read_int16().asLong
      case Tag.VOID => 0.asLong
      case Tag.BOOLEAN => read_int8().asLong
      // see docs, this is an objectRef and not a literally encoded Utf8 string
      case Tag.STRING => readObjectID().asLong
      case Tag.THREAD => readObjectID().asLong
      case Tag.THREAD_GROUP => readObjectID().asLong
      case Tag.CLASS_LOADER => readObjectID().asLong
      case Tag.CLASS_OBJECT => readObjectID().asLong
      case _ => throw new RuntimeException(s"Unexpected type tag '${tag}'")
    Value(tag, value)

  extension (v: Byte) {
    inline def asLong : Long = v.asInstanceOf[Long]
  }
  extension (v: Short) {
    inline def asLong : Long = v.asInstanceOf[Long]
  }
  extension (v: Int) {
    inline def asLong : Long = v.asInstanceOf[Long]
  }
  extension (v: Long) {
    inline def asLong : Long = v
  }
}
