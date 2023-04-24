package dwr.jdwp_proxy

import java.util.concurrent.atomic.AtomicInteger

final class PacketIdGenerator(initialValue: Int) {
  def this() = this(0)
  private val nextID = AtomicInteger(initialValue)
  def next(): Int = nextID.getAndIncrement()
}
