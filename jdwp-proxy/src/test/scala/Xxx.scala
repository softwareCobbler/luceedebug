package co.helmethair.scalatest.example

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import dwr.jdwp.*
import dwr.jdwp_proxy.{JdwpProxy, JdwpProxyConfig}
import dwr.packet_parser.PacketParser
import dwr.utils.DummySocketIO

def stubIdSizes = packet.reply.virtual_machine.IdSizes(8,8,8,8,8)

class SomeCodeTest extends AnyFunSpec with Matchers {
  describe("bleem") {
    it("uhok") {
      import packet.command.event.Event.Breakpoint
      val loc1 = Location(typeTag = TypeTag.CLASS, classID = 1, methodID = 2, index = 3)
      val loc2 = Location(typeTag = TypeTag.CLASS, classID = 4, methodID = 5, index = 6)
      val event1 : Breakpoint = Breakpoint(requestID_ = 1, thread = ThreadID(10), location = loc1)
      val event2 : Breakpoint = Breakpoint(requestID_ = 2, thread = ThreadID(10), location = loc2)

      event1 should not be event2

      val compositeEvent = packet.command.event.Composite(SuspendPolicy.EVENT_THREAD, Seq(event1, event2))

      {
        val v = JdwpProxy.compositeEventFromSingleEvent(compositeEvent, event1)
        v.suspendPolicy shouldBe SuspendPolicy.EVENT_THREAD
        v.events.size shouldBe 1
        v.events.head.asInstanceOf[Breakpoint] shouldBe event1
      }

      {
        val v = JdwpProxy.compositeEventFromSingleEvent(compositeEvent, event2)
        v.suspendPolicy shouldBe SuspendPolicy.EVENT_THREAD
        v.events.size shouldBe 1
        v.events.head.asInstanceOf[Breakpoint] shouldBe event2
      }

    }
  }

  describe("uh2") {
    it("uhok2") {
      val socketIO = DummySocketIO()
      val parser = PacketParser()
      val proxyConfig = JdwpProxyConfig(socketIO, parser, stubIdSizes)
      val proxy = JdwpProxy(proxyConfig)
      val thread = new Thread(() => proxy.start())
      thread.start()

    }
  }
  // describe("i8") {
  //   for (i <- 0 to 0xFF) do
  //     it(i.toString()) {
  //       val a = V.int8_to_beI8(i.asInstanceOf[Byte])
  //       val b = V.beI8_to_int8(a)
  //       b shouldBe i.asInstanceOf[Byte]
  //     }
  describe("i32") {
//      for (i <- 0xEEEE to 0xFFFF) {
//        it(i.toString()) {
//          val x = i
//          val a = V.int32_to_beI32(x)
//          val b = V.beI32_to_int32(a)
//          b shouldBe x
//        }
//      }

    // for (i <- 0 to 0xFFFF) do
    //   it(i.toString()) {
    //     val a = V.int8_to_beI8(i.asInstanceOf[Byte])
    //     val b = V.beI8_to_int8(a)
    //     b shouldBe i.asInstanceOf[Byte]
    //   }

    // it("i16") {
    //   for (i <- 0 to 0xFFFF) do
    //     V.beI16_to_int16(V.int16_to_beI16(i.asInstanceOf[Short])) shouldBe i
    // }

  }
}