package dwr.utils

import scala.collection.IndexedSeqView

object ByteWrangler {
  def int8_to_beI8(v: Byte) : Array[Byte] = Array[Byte](v)

  def beI8_to_int8(vs: IndexedSeqView[Byte]) : Byte = vs(0)
  def beI8_to_int8(vs: Array[Byte]) : Byte = vs(0)

  def int16_to_beI16(v: Short) : Array[Byte] =
    Array[Byte](
      ((v >>> 8) & 0xFF).asInstanceOf[Byte],
      ((v >>> 0) & 0xFF).asInstanceOf[Byte],
    )

  def beI16_to_int16(hi: Byte, lo: Byte) : Short =
    (((hi & 0xFF) << 8) | ((lo & 0xFF) << 0)).asInstanceOf[Short]

  def beI16_to_int16(vs: IndexedSeqView[Byte]) : Short = beI16_to_int16(vs(0), vs(1))
  def beI16_to_int16(vs: Array[Byte]) : Short = beI16_to_int16(vs(0), vs(1))

  def int32_to_beI32(v: Int) : Array[Byte] =
    Array[Byte](
      ((v >>> 24) & 0xFF).asInstanceOf[Byte],
      ((v >>> 16) & 0xFF).asInstanceOf[Byte],
      ((v >>> 8) & 0xFF).asInstanceOf[Byte],
      ((v >>> 0) & 0xFF).asInstanceOf[Byte],
    )

  def beI32_to_int32(b1: Byte, b2: Byte, b3: Byte, b4: Byte) : Int =
    ((b1 & 0xFF) << 24)
      | ((b2 & 0xFF) << 16)
      | ((b3 & 0xFF) << 8)
      | ((b4 & 0xFF) << 0)

  def beI32_to_int32(vs: IndexedSeqView[Byte]) : Int = beI32_to_int32(vs(0), vs(1), vs(2), vs(3))
  def beI32_to_int32(vs: Array[Byte]) : Int = beI32_to_int32(vs(0), vs(1), vs(2), vs(3))

  def int64_to_beI64(v: Long) : Array[Byte] =
    Array[Byte](
      ((v >>> 56) & 0xFF).asInstanceOf[Byte],
      ((v >>> 48) & 0xFF).asInstanceOf[Byte],
      ((v >>> 40) & 0xFF).asInstanceOf[Byte],
      ((v >>> 32) & 0xFF).asInstanceOf[Byte],
      ((v >>> 24) & 0xFF).asInstanceOf[Byte],
      ((v >>> 16) & 0xFF).asInstanceOf[Byte],
      ((v >>> 8) & 0xFF).asInstanceOf[Byte],
      ((v >>> 0) & 0xFF).asInstanceOf[Byte],
    )

  def beI64_to_int64(vs: IndexedSeqView[Byte]) : Long =
    ((vs(0) & 0xFF).asInstanceOf[Long] << 56)
      | ((vs(1) & 0xFF).asInstanceOf[Long] << 48)
      | ((vs(2) & 0xFF).asInstanceOf[Long] << 40)
      | ((vs(3) & 0xFF).asInstanceOf[Long] << 32)
      | ((vs(4) & 0xFF).asInstanceOf[Long] << 24)
      | ((vs(5) & 0xFF).asInstanceOf[Long] << 16)
      | ((vs(6) & 0xFF).asInstanceOf[Long] << 8)
      | ((vs(7) & 0xFF).asInstanceOf[Long] << 0)

  def beI64_to_int64(vs: Array[Byte]) : Long = beI64_to_int64(vs.view)
}