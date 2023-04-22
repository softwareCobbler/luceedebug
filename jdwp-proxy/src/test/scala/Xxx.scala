package co.helmethair.scalatest.example

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import XXX._

class SomeCodeTest extends AnyFunSpec with Matchers {
  // describe("i8") {
  //   for (i <- 0 to 0xFF) do
  //     it(i.toString()) {
  //       val a = V.int8_to_beI8(i.asInstanceOf[Byte])
  //       val b = V.beI8_to_int8(a)
  //       b shouldBe i.asInstanceOf[Byte]
  //     }
  describe("i32") {
      for (i <- 0xEEEE to 0xFFFF) {
        it(i.toString()) {
          val x = i
          val a = V.int32_to_beI32(x)
          val b = V.beI32_to_int32(a)
          b shouldBe x
        }
      }
    
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