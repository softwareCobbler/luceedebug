package dwr.utils

object ByteWrangler {
  def int8_to_beI8(v: Byte) : Array[Byte] = Array[Byte](v)

  def beI8_to_int8(vs: Array[Byte]) : Byte = vs(0)

  def int16_to_beI16(v: Short) : Array[Byte] =
    Array[Byte](
      ((v >>> 8) & 0xFF).asInstanceOf[Byte],
      ((v >>> 0) & 0xFF).asInstanceOf[Byte],
    )

  def beI16_to_int16(vs: Array[Byte]) : Short = 
    val hi = (vs(0) & 0xFF) << 8
    val lo = (vs(1) & 0xFF) << 0
    (hi | lo).asInstanceOf[Short]

  def int32_to_beI32(v: Int) : Array[Byte] =
    Array[Byte](
      ((v >>> 24) & 0xFF).asInstanceOf[Byte],
      ((v >>> 16) & 0xFF).asInstanceOf[Byte],
      ((v >>> 8) & 0xFF).asInstanceOf[Byte],
      ((v >>> 0) & 0xFF).asInstanceOf[Byte],
    )

  def beI32_to_int32(vs: Array[Byte]) : Int = 
    ((vs(0) & 0xFF) << 24)
      | ((vs(1) & 0xFF) << 16)
      | ((vs(2) & 0xFF) << 8)
      | ((vs(3) & 0xFF) << 0)

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

  def beI64_to_int64(vs: Array[Byte]) : Long = 
    ((vs(0) & 0xFF).asInstanceOf[Long] << 56)
      | ((vs(1) & 0xFF).asInstanceOf[Long] << 48)
      | ((vs(2) & 0xFF).asInstanceOf[Long] << 40)
      | ((vs(3) & 0xFF).asInstanceOf[Long] << 32)
      | ((vs(4) & 0xFF).asInstanceOf[Long] << 24)
      | ((vs(5) & 0xFF).asInstanceOf[Long] << 16)
      | ((vs(6) & 0xFF).asInstanceOf[Long] << 8)
      | ((vs(7) & 0xFF).asInstanceOf[Long] << 0)
}