package dwr.jdwp.packet.raw

import dwr.utils.ByteWrangler

import scala.collection.IndexedSeqView

sealed abstract class FinishedPacket {
  protected var id_ : Int
  protected var flags_ : Byte
  protected var body_ : IndexedSeqView[Byte]
  protected var raw_ : Array[Byte]

  def withSwappedID(newID: Int, f: FinishedPacket => Unit): Unit = this.synchronized {
    val savedID = ID
    try {
      setID(newID)
      f(this)
    }
    finally {
      setID(savedID)
    }
  }

  def ID: Int = id_

  def flags: Byte = flags_

  def body: IndexedSeqView[Byte] = body_

  def raw: Array[Byte] = raw_

  private def setID(v: Int): Unit =
    id_ = v
    val raw = ByteWrangler.int32_to_beI32(v)
    raw_(4) = raw(0)
    raw_(5) = raw(1)
    raw_(6) = raw(2)
    raw_(7) = raw(3)
}

class Command(
               val length: Int,
               id: Int,
               flags: Byte,
               val commandSet: Byte,
               val command: Byte,
               body: IndexedSeqView[Byte],
               raw: Array[Byte]
             ) extends FinishedPacket {
  protected var id_ = id
  protected var flags_ = flags
  protected var body_ = body
  protected var raw_ = raw
}

class Reply(
             val length: Int,
             id: Int,
             flags: Byte,
             val errorCode: Short,
             body: IndexedSeqView[Byte],
             raw: Array[Byte]
           ) extends FinishedPacket {
  protected var id_ = id
  protected var flags_ = flags
  protected var body_ = body
  protected var raw_ = raw
}
