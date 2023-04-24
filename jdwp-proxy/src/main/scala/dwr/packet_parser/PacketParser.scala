package dwr.packet_parser

import scala.collection.mutable.ArrayBuffer
import scala.math.{min,max}
import scala.annotation.tailrec

import dwr.reader._
import dwr.utils.ByteWrangler
import scala.collection.IndexedSeqView

import dwr.jdwp.packet.raw

class PacketBuilder {
  private final inline val HEADER_LENGTH = 11

  val buffer = new ArrayBuffer[Byte](1024)

  private val parsedLength = LengthReader()

  private final class LengthReader() {
    private final inline val NIL = -1;
    private var cached_ = NIL

    def get() : Int =
      if cached_ == NIL
      then {
        cached_ = ByteWrangler.beI32_to_int32(buffer(0), buffer(1), buffer(2), buffer(3))
        cached_
      }
      else cached_

    inline def clear() : Unit = cached_ = NIL
  }

  private inline def reset() : Unit =
    buffer.clear()
    parsedLength.clear()

  def consumeAndReset() : raw.FinishedPacket =
    try {
      val copy = buffer.toArray
      val bodyRef = copy.view.takeRight(copy.length - HEADER_LENGTH)
      if isReply
      then
        raw.Reply(
          length = parsedLength.get(),
          id = ByteWrangler.beI32_to_int32(buffer(4), buffer(5), buffer(6), buffer(7)),
          flags = buffer(8),
          errorCode = ByteWrangler.beI16_to_int16(buffer(9), buffer(10)),
          body = bodyRef,
          raw = copy
        )
      else
        raw.Command(
          length = parsedLength.get(),
          id = ByteWrangler.beI32_to_int32(buffer(4), buffer(5), buffer(6), buffer(7)),
          flags = buffer(8),
          commandSet = buffer(9),
          command = buffer(10),
          body = bodyRef,
          raw = copy
        )
    }
    finally {
      reset()
    }

  private inline def flags : Byte = buffer(8)
  private inline def isReply : Boolean = (flags & 0x80) > 0
  private inline def isCommand : Boolean = !isReply

  inline def packetLengthIncludingHeader : Int = parsedLength.get()
  inline def packetLengthNoHeader : Int = parsedLength.get() - HEADER_LENGTH
}

class PacketParser {
  private final inline val HEADER_LENGTH = 11
  private enum State:
    case Header()
    case Body()
    case Done()

  private def startState : State = State.Header()

  private var state = startState
  private var builder = PacketBuilder()

  private case class ParseResult(nextState: State, remaining: Array[Byte])

  private def parseHeader(buffer: Array[Byte]) : ParseResult =
    if builder.buffer.length > HEADER_LENGTH
    then throw new RuntimeException(s"On entry to parseHeader, builder buffer length was ${builder.buffer.length}")

    val needed = max(0, HEADER_LENGTH - builder.buffer.length)
    val consumable = min(buffer.length, needed)

    builder.buffer.addAll(buffer.take(consumable))

    if builder.buffer.length != HEADER_LENGTH
    then ParseResult(State.Header(), buffer.drop(consumable))
    else
      if builder.buffer.length == builder.packetLengthIncludingHeader
      then ParseResult(State.Done(), buffer.drop(consumable))
      else ParseResult(State.Body(), buffer.drop(consumable))

  private def parseBody(buffer: Array[Byte]) : ParseResult =
    val consumable = min(buffer.length, builder.packetLengthIncludingHeader - builder.buffer.length)

    if consumable < 0
    then throw new RuntimeException(s"buffer consumable count was LT 0: ${consumable}")

    builder.buffer.addAll(buffer.take(consumable))

    if (builder.buffer.length > builder.packetLengthIncludingHeader)
    then throw new RuntimeException(s"current builder length is ${builder.buffer.length} but target size is ${builder.packetLengthIncludingHeader} (all sizes include header)")

    ParseResult(
      nextState = if builder.buffer.length == builder.packetLengthIncludingHeader then State.Done() else State.Body(),
      remaining = buffer.drop(consumable)
    )

  private def unreachable[T] : T = throw RuntimeException("unreachable")

  private def parse(buffer: Array[Byte]) : ParseResult =
    state match
      case State.Header() => parseHeader(buffer)
      case State.Body() => parseBody(buffer)
      case State.Done() => unreachable

  @tailrec
  private def consume(buffer: Array[Byte], completed: List[raw.FinishedPacket]) : List[raw.FinishedPacket] =
    if buffer.length == 0
    then return completed

    parse(buffer) match
      case ParseResult(State.Done(), remaining) =>
        state = startState
        consume(remaining, completed :+ builder.consumeAndReset())
      case ParseResult(nextState, remaining) =>
        state = nextState
        consume(remaining, completed)

  def consume(buffer: Array[Byte]) : List[raw.FinishedPacket] = consume(buffer, List())
}
