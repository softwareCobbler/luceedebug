package dwr.jdwp.packet.reply.virtual_machine

import dwr.jdwp.packet.*

class Dispose extends JdwpReply with BodyToWire {
  def bodyToWire()(using idSizes: IdSizes) : Array[Byte] = new Array[Byte](0)
}
