package XXX

import JdwpProxy.{HANDSHAKE_BYTES, HANDSHAKE_STRING}

import java.io.{IOException, InputStream, OutputStream}
import java.net.{InetAddress, InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}
import scala.util.control.Breaks._
import scala.annotation.tailrec
import scala.collection.mutable.HashMap
import java.util.concurrent.atomic.AtomicInteger
import java.net.ServerSocket
import scala.math.{min}
import scala.util.control.Exception.By
import XXX.packet.reply.IdSizes
import XXX.packet.reply
import XXX.packet.command.EventRequest
import XXX.JdwpSizedReader.readThreadID
import XXX.JdwpSizedReader.readTaggedObjectID

@main
def ok() : Unit =
  val ARRAY        : Byte = '['
  println(ARRAY)
  //JdwpProxy(host="localhost", port=9999, _ => {})


abstract class ISocketIO {
  def chunkedReader(chunkSize: Int) : () => Array[Byte]
  def write(bytes: Array[Byte], timeout_ms: Int) : Unit
  def read(n: Int, timeout_ms: Int) : Array[Byte]
}

class SocketIO(private val inStream: InputStream, private val outStream: OutputStream) extends ISocketIO {
  private implicit val executionContext : ExecutionContext = ExecutionContext.fromExecutor(
    Executors.newFixedThreadPool(3)
  )

  def chunkedReader(chunkSize: Int) : () => Array[Byte] = {
    val buffer = new Array[Byte](chunkSize);
    () => {
      val readSize = inStream.read(buffer)
      buffer.slice(0, readSize)
    }
  }

  def write(bytes: Array[Byte], timeout_ms: Int) : Unit =
    val duration = Duration(timeout_ms, MILLISECONDS)
    Await.result(
      Future {
        outStream.write(bytes)
        outStream.flush()
      },
      duration
    )

  def read(n: Int, timeout_ms: Int) : Array[Byte] =
    val duration = Duration(timeout_ms, MILLISECONDS)
    val bytes = Await.result(Future{inStream.readNBytes(n)}, duration)
    if bytes.length != n
      then throw new IOException(s"Expected ${n} bytes but read ${bytes.length}")
      else bytes
}

/**
 * java is big endian, network is big endian
 */
class CheckedReader(raw: Array[Byte]) {
  private var index : Int = 0

  private def getAndAdvance(len: Int) : Array[Byte] =
    val ret = raw.slice(index, index + len)
    ret(len - 1) // dies on out-of-bounds right? (slice allows out-of-bounds slices...?)
    index += len
    ret

  def readN(n: Int) : Array[Byte] = getAndAdvance(n)
  def read_int8(): Byte = ByteWrangler.beI8_to_int8(readN(1))
  def read_int16(): Short = ByteWrangler.beI16_to_int16(readN(2))
  def read_int32() : Int = ByteWrangler.beI32_to_int32(readN(4))
  def read_int64() : Long = ByteWrangler.beI64_to_int64(readN(8))
}

final class Location(val typeTag: Byte, val classID: Long, val methodID: Long, val index: Long)

class JdwpSizedReader(idSizes: IdSizes, raw: Array[Byte]) extends CheckedReader(raw) {
  private def read4Or8(size: Int) : Long =
    size match
      case 4 => read_int32().asInstanceOf[Long]
      case 8 => read_int64()
      case _ => throw new RuntimeException(s"unexpected field size ${idSizes.fieldIDSize}")
  
  def readBoolean() : Boolean = if read_int8() == 0 then false else true

  def readFieldID() = read4Or8(idSizes.fieldIDSize)
  def readMethodID() = read4Or8(idSizes.methodIDSize)
  def readObjectID() = read4Or8(idSizes.objectIDSize)
  def readReferenceTypeID() = read4Or8(idSizes.referenceTypeIDSize)
  def readFrameID() = read4Or8(idSizes.frameIDSize)

  def readThreadID() = readObjectID()
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
    String(bytes, StandardCharsets.UTF_8)

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
      case Tag.ARRAY => readObjectID().asInstanceOf[Long]
      case Tag.BYTE => read_int8().asInstanceOf[Long]
      case Tag.CHAR => read_int16().asInstanceOf[Long]
      case Tag.OBJECT => readObjectID().asInstanceOf[Long]
      case Tag.FLOAT => read_int32().asInstanceOf[Long]
      case Tag.DOUBLE => read_int64().asInstanceOf[Long]
      case Tag.INT => read_int32().asInstanceOf[Long]
      case Tag.LONG => read_int64().asInstanceOf[Long]
      case Tag.SHORT => read_int16().asInstanceOf[Long]
      case Tag.VOID => 0.asInstanceOf[Long]
      case Tag.BOOLEAN => read_int8().asInstanceOf[Long]
      // see docs, this is an objectRef and not a literally encoded Utf8 string
      case Tag.STRING => readObjectID().asInstanceOf[Long]
      case Tag.THREAD => readObjectID().asInstanceOf[Long]
      case Tag.THREAD_GROUP => readObjectID().asInstanceOf[Long]
      case Tag.CLASS_LOADER => readObjectID().asInstanceOf[Long]
      case Tag.CLASS_OBJECT => readObjectID().asInstanceOf[Long]
      case _ => throw new RuntimeException(s"Unexpected type tag '${tag}'")
    Value(tag, value)
    
}

final class TaggedObjectID(tag: Byte, value: Long)
final class Value(tag: Byte, value: Long)

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

  def int64_to_beI64(v: Int) : Array[Byte] =
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

class LuceeDebugJdwpProxy(
  jdwpHost: String,
  jdwpPort: Int,
  luceeClientHost: String,
  luceeClientPort: Int,
  jvmClientHost: String,
  jvmClientPort: Int
) {
  private class ProxyThread(val proxy: JdwpProxy, val thread: Thread)
  private val proxyThread = {
    var proxy : Option[JdwpProxy] = None
    val lock = Object()
    lock.synchronized {
      val thread = new Thread(() => 
        new JdwpProxy(
          jdwpHost,
          jdwpPort, 
          p => lock.synchronized {
            proxy = Some(p)
            lock.notify()
          }
        )
        ()
      )
      thread.start()
      lock.wait()
      ProxyThread(proxy.get, thread)
    }
  }

  enum ConnectionState:
    case Connected(client: ClientFacingProxyClient)
    case Listening(client: Future[ClientFacingProxyClient])
  import ConnectionState._

  private def listenOn(host: String, port: Int) : Socket = 
    val socket = new ServerSocket()
    val inetAddr = new InetSocketAddress(host, port);
    socket.bind(inetAddr)
    socket.accept()
  
  implicit val ec : ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))
  
  var lucee = connectLucee()
  var java = connectJava()
  
  private def connectLucee() : ConnectionState =
    Listening({
      val future = Future {
        val socket = listenOn(luceeClientHost, luceeClientPort)
        proxyThread.proxy.createAndRegisterClient(
          "lucee-frontend",
          socket,
          () => connectLucee()
        )
      }
      future.onComplete(v => {
        v match
          case Success(client) => { lucee = Connected(client) }
          case Failure(e) => throw e
      })
      future
    })

  private def connectJava() : ConnectionState =
    Listening({
      val future = Future {
        val socket = listenOn(jvmClientHost, jvmClientPort)
        proxyThread.proxy.createAndRegisterClient(
          "java-frontend",
          socket,
          () => connectJava(), 
        )
      }
      future.onComplete(v => {
        v match
          case Success(client) => { java = Connected(client) }
          case Failure(e) => throw e
      })
      future
    })
}

class ClientFacingProxyClient(
  val id: Int,
  val io: ISocketIO,
  val receiveFromClient: (client: ClientFacingProxyClient, bytes: Array[Byte]) => Unit,
  val receiveFromJVM: Array[Byte] => Unit,
  val onClientDisconnect : () => Unit,
  val name: String
) {
  private val thread = {
    val thread = new Thread(() => {
      val reader = io.chunkedReader(1024)
      while true do
        receiveFromClient(this, reader())
    })
    thread.start()
    thread
  }
}

class JvmFacingProxyClient(
  val id: Int,
  val io: ISocketIO,
  val receiveFromJVM: Array[Byte] => Unit,
  val receiveFromClient: (ClientFacingProxyClient, rawpacket.FinishedPacket) => Unit,
) {
  private val thread = {
    val thread = new Thread(() => {
      val reader = io.chunkedReader(1024)
      while true do
        receiveFromJVM(reader())
    })
    thread.start()
    thread
  }
}

class JdwpProxy(host: String, port: Int, cb: JdwpProxy => Unit) {
  cb(this)

  import OnCommandReplyStrategy._
  import scala.collection.concurrent.TrieMap

  class PacketOrigin(val originalID: Int, val client: ClientFacingProxyClient)

  type OnCommandReplyCallback = rawpacket.Reply => Unit
  
  private object OnCommandReplyStrategy {
    def recordEventRequestAndForward(
      origin: PacketOrigin,
      onEventReceiptStrategy: OnEventReceiptStrategy,
      request: packet.command.event_request.Set
    ) : OnCommandReplyCallback =
      rawPacket => {
        // parse
        val reply = packet.reply.event_request.Set.fromWire(idSizes, rawPacket.raw)
  
        // record
        activeEventRequests_.addOne((reply.requestID, onEventReceiptStrategy))
        if request.eventKind == EventKind.BREAKPOINT
        then bpEventRequestsByClient_
          .getOrElseUpdate(origin.client.id, TrieMap[Int, Unit]())
          .addOne((reply.requestID, ()))
  
        // forward                  
        rawPacket.withSwappedID(origin.originalID, mutatedPacket => origin.client.receiveFromJVM(mutatedPacket.raw))
      }

    def justForward(
      origin: PacketOrigin
    ) : OnCommandReplyCallback =
      rawPacket => {
        rawPacket.withSwappedID(origin.originalID, mutatedPacket => origin.client.receiveFromJVM(mutatedPacket.raw))
      }

    def doAfterNResponses(n: Int, callback: OnCommandReplyCallback) : OnCommandReplyCallback =
      var responses = AtomicInteger()
      rawPacket => {
        if responses.incrementAndGet() == n
        then callback(rawPacket)
        else ()
      }

    def sendSomeExactResponse(client: ClientFacingProxyClient, bytes: Array[Byte]) : OnCommandReplyCallback =
      rawPacket => {
        client.receiveFromJVM(bytes)
      }
  }
  
  enum OnEventReceiptStrategy:
    case JustForward(client: ClientFacingProxyClient)
    case ForwardAndDiscard(client: ClientFacingProxyClient)

  private val nextClientID = AtomicInteger()
  private val nextPacketID = AtomicInteger()
  private val (jvmSocketIO, parser, idSizes) = JdwpProxy.getSocketIOFromHandshake(host, port)
  private val jvm : JvmFacingProxyClient = {
    JvmFacingProxyClient(
      id = nextClientID.getAndIncrement(),
      io = jvmSocketIO,
      receiveFromJVM = bytes => {
        for (rawPacket <- parser.consume(bytes)) do
          rawPacket match
            case rawPacket : rawpacket.Command =>
              Command
                .maybeGetParser(rawPacket)
                .map(_.fromWire(idSizes, rawPacket.raw)) match
                  case Some(compositeEvent : packet.command.event.Composite) =>
                    for (event <- compositeEvent.events) do
                      import OnEventReceiptStrategy._
                      activeEventRequests_.get(event.requestID) match
                        case Some(JustForward(client)) =>
                          client.receiveFromJVM(rawPacket.raw)
                        case Some(ForwardAndDiscard(client)) =>
                          client.receiveFromJVM(rawPacket.raw)
                          activeEventRequests_.remove(event.requestID)
                        case Some(JustDiscard()) =>
                          activeEventRequests_.remove(event.requestID)
                        case Some(SendSomeExactResponseAndDiscard(client, bytes)) =>
                          client.receiveFromJVM(bytes)
                          activeEventRequests_.remove(event.requestID)

                  case _ => 0
              for ((_, client) <- clients_) do
                // split into event id streams ...
                client.receiveFromJVM(rawPacket.raw)
            case rawPacket : rawpacket.Reply =>
              commandsAwaitingReply_.get(rawPacket.ID) match
                case Some(callback) =>
                  callback(rawPacket)
                  commandsAwaitingReply_.remove(rawPacket.ID)
                case None => throw new RuntimeException(s"Reply packet from JVM having id=${rawPacket.ID} was unexpected; no handler is available.")
      },
      receiveFromClient = (client, rawPacket) => {
        rawPacket match
          case command : rawpacket.Command =>
            Command
              .maybeGetParser(command)
              .map(_.fromWire(idSizes, command.data)) match
                case Some(command : packet.command.event_request.ClearAllBreakpoints) =>
                  final class W(val bpRequestID: Int, val freshPacketID: Int, val bytes: Array[Byte])
                  val ws = bpEventRequestsByClient_
                    .getOrElseUpdate(client.id, TrieMap())
                    .keys
                    .map(bpRequestID => {
                      val freshPacketID = nextPacketID.getAndIncrement()
                      val bytes = ReplyPacket.toWire(nextPacketID.getAndIncrement(), packet.command.event_request.Clear(EventKind.BREAKPOINT, bpRequestID))
                      W(bpRequestID, freshPacketID, bytes)
                    })
                    .toSeq

                  if ws.length > 0
                  then
                    val onReplyStrategy = OnCommandReplyStrategy.doAfterNResponses(
                      ws.length,
                      proxyClient => {
                        ws.foreach(w => clearEventRequest(client, w.bpRequestID))
                        val synthesizedResponse =  ReplyPacket.toWire(rawPacket.ID, packet.reply.event_request.ClearAllBreakpoints())
                        client.receiveFromJVM(synthesizedResponse)
                      }
                    )

                    ws.foreach(w => {
                      commandsAwaitingReply_.addOne((w.freshPacketID, onReplyStrategy))
                      jvmSocketIO.write(w.bytes, 1000)
                    })
                case Some(command : packet.command.event_request.Clear) =>
                  clearEventRequest(client, command.eventRequestID)
                  
                  val onReplyStrategy = OnCommandReplyStrategy.justForward(PacketOrigin(rawPacket.ID, client))
                  
                  val newID = nextPacketID.getAndIncrement()
                  
                  command.withSwappedID(
                    newID,
                    packet => {
                      commandsAwaitingReply_.addOne((newID, onReplyStrategy))
                      jvmSocketIO.write(packet.raw, 1000)
                    }
                  )
                case Some(command : packet.command.event_request.Set) =>
                  val eventReceiptStrategy = if command.hasCount()
                    then OnEventReceiptStrategy.ForwardAndDiscard(client)
                    else OnEventReceiptStrategy.JustForward(client)
                  
                  val onReplyStrategy = OnCommandReplyStrategy
                    .recordEventRequestAndForward(
                      PacketOrigin(rawPacket.ID, client),
                      eventReceiptStrategy,
                      command
                    )
                  
                  val newID = nextPacketID.getAndIncrement()
                  
                  rawPacket.withSwappedID(
                    newID,
                    packet => {
                      commandsAwaitingReply_.addOne((newID, onReplyStrategy))
                      jvmSocketIO.write(packet.raw, 1000)
                    }
                  )
                case _ =>
                  throw new RuntimeException(s"unhandled command received from client '${client.name}', commandSet=${command.commandSet} command=${command.command}")
          case reply : rawpacket.Reply =>
            throw new RuntimeException(s"unexpected reply packet from client '${client.name}'")
      }
    )
  }

  private val clients_ = HashMap[Int, ClientFacingProxyClient]()
  private val commandsAwaitingReply_ = TrieMap[Int, rawpacket.Reply => Unit]()
  private val activeEventRequests_ = TrieMap[Int, OnEventReceiptStrategy]()
  private val bpEventRequestsByClient_ = TrieMap[Int, TrieMap[Int, Unit]]()

  private def clearEventRequest(client: ClientFacingProxyClient, eventRequestID: Int) : Unit =
    activeEventRequests_.remove(eventRequestID)
    bpEventRequestsByClient_.get(client.id).foreach(bps => bps.remove(eventRequestID))

  def createAndRegisterClient(
    name: String,
    clientSocket: Socket,
    onClientDisconnect: () => Unit,
  ) : ClientFacingProxyClient =
    val socketIO = SocketIO(clientSocket.getInputStream(), clientSocket.getOutputStream())
    val parser = PacketParser()
    val client : ClientFacingProxyClient = ClientFacingProxyClient(
      id = nextClientID.getAndIncrement(),
      io = socketIO,
      receiveFromClient = (client, bytes) => {
        for (packet <- parser.consume(bytes)) do
          jvm.receiveFromClient(client, packet)
      },
      receiveFromJVM = bytes => socketIO.write(bytes, /*ms*/5000),
      onClientDisconnect = onClientDisconnect,
      name = name
    )
    clients_.put(client.id, client)
    client

  def unregisterClient(client: ClientFacingProxyClient) : Unit = clients_.remove(client.id)
}

object JdwpProxy {
  private val HANDSHAKE_STRING: String = "JDWP-Handshake"
  private val HANDSHAKE_BYTES: Array[Byte] = HANDSHAKE_STRING.getBytes(StandardCharsets.US_ASCII)
  
  @throws(classOf[IOException])
  private def getSocketIOFromHandshake(host: String, port: Int) : (SocketIO, PacketParser, packet.reply.IdSizes) =
    val socket = new Socket()
    val inetAddr = new InetSocketAddress(host, port);
    socket.connect(inetAddr, /*ms*/5000);
    val socketIO = SocketIO(socket.getInputStream(), socket.getOutputStream())
    
    socketIO.write(HANDSHAKE_BYTES, 1000)
    val handshakeIn = socketIO.read(HANDSHAKE_STRING.length, 5000)
    val receivedHandshake = String(CheckedReader(handshakeIn).readN(HANDSHAKE_STRING.length), StandardCharsets.UTF_8)

    if !receivedHandshake.equals(HANDSHAKE_STRING)
    then throw new IOException(s"Bad JDWP handshake, got '${receivedHandshake}'")

    // do the "size" message first?
    socketIO.write(
      bytes = CommandPacket.toWire(0, packet.command.IdSizes()),
      timeout_ms = 5000
    )
    
    val parser = PacketParser()
    val parsed = ArrayBuffer[rawpacket.FinishedPacket]()
    val chunkedReader = socketIO.chunkedReader(128)
    var idSizes : Option[packet.reply.IdSizes] = None
    breakable {
      import packet.reply.IdSizes
      while true do
        for (packet <- parser.consume(chunkedReader())) do
          if packet.isInstanceOf[rawpacket.Reply] && packet.ID == 0
          then
            idSizes = Some(IdSizes.fromWire(IdSizes.dummy, packet.data))
            break
          else parsed += packet
    }

    (socketIO, parser, idSizes.get)
    
}

package rawpacket:
  class PacketBuilder {
    val length = new ArrayBuffer[Byte](4)
    val id = new ArrayBuffer[Byte](4)
    val last3 = new ArrayBuffer[Byte](3)
    val body = new ArrayBuffer[Byte](64)
    val raw = new ArrayBuffer[Byte](64)

    def toFinishedPacket() : FinishedPacket =
      if isReply
      then 
        val last3Reader = CheckedReader(last3.toArray)
        rawpacket.Reply(
          length = readLength(),
          id = readID(),
          flags = last3Reader.read_int8(),
          errorCode = last3Reader.read_int16(),
          data = body.toArray,
          raw = raw.toArray
        )
      else
        val last3Reader = CheckedReader(last3.toArray)
        rawpacket.Command(
          length = readLength(),
          id = readID(),
          flags = last3Reader.read_int8(),
          commandSet = last3Reader.read_int8(),
          command = last3Reader.read_int8(),
          data = body.toArray,
          raw = raw.toArray
        )
    
    def flags : Byte = last3(0)
    def isReply : Boolean = (flags & 0x80) > 0
    def isCommand : Boolean = !isReply

    def readLength() : Int = CheckedReader(length.toArray).read_int32()
    def readID() : Int = CheckedReader(id.toArray).read_int32()
  }

  sealed abstract class FinishedPacket {
    protected var id_ : Int
    protected var flags_ : Byte
    protected var data_ : Array[Byte]
    protected var raw_ : Array[Byte]

    def withSwappedID(newID: Int, f: FinishedPacket => Unit) : Unit = this.synchronized {
      val savedID = ID
      try {
        setID(newID)
        f(this)
      }
      finally {
        setID(savedID)
      }
    }
    
    def ID : Int = id_
    def flags : Byte = flags_
    def data : Array[Byte] = data_
    def raw : Array[Byte] = raw_

    private def setID(v: Int) : Unit =
      id_ = v
      raw_(0) = ((v & 0xFF000000) >> 24).asInstanceOf[Byte]
      raw_(1) = ((v & 0x00FF0000) >> 16).asInstanceOf[Byte]
      raw_(2) = ((v & 0x0000FF00) >> 8).asInstanceOf[Byte]
      raw_(3) = ((v & 0x000000FF) >> 0).asInstanceOf[Byte]
      
  }
  class Command(
    val length: Int,
    id: Int,
    flags: Byte,
    val commandSet: Byte,
    val command: Byte,
    data: Array[Byte],
    raw: Array[Byte]
  ) extends FinishedPacket {
    protected var id_ = id
    protected var flags_ = flags
    protected var data_ = data
    protected var raw_ = raw
  }
  class Reply(
    val length: Int,
    id: Int,
    flags: Byte,
    val errorCode: Short,
    data: Array[Byte],
    raw: Array[Byte]
  ) extends FinishedPacket {
    protected var id_ = id
    protected var flags_ = flags
    protected var data_ = data
    protected var raw_ = raw
  }

class PacketParser {
  private val HEADER_LENGTH = 11
  enum State:
    case HeaderLength()
    case HeaderID()
    case HeaderLast3()
    case Body()
    case Done()
  
  private def startState : State = State.HeaderLength()

  private var state = startState
  private var builder = rawpacket.PacketBuilder() 

  private case class ParseResult(nextState: State, remaining: Array[Byte])

  private def pushByte(builderTarget: ArrayBuffer[Byte], byte: Byte) : Unit =
    builderTarget += byte
    builder.raw += byte
  
  private def parseLength(buffer: Array[Byte]) : ParseResult =
    val consumable = min(buffer.length, 4 - builder.length.length)
    var consumed = 0
    val checkedReader = CheckedReader(buffer)
    while consumed != consumable do
      pushByte(builder.length, checkedReader.read_int8())
      consumed += 1

    ParseResult(
      nextState = if builder.length.length == 4 then State.HeaderID() else State.HeaderLength(),
      remaining = buffer.slice(consumed, buffer.length)
    )

  private def parseId(buffer: Array[Byte]) : ParseResult =
    var consumable = min(buffer.length, 4 - builder.id.length)
    var consumed = 0
    val checkedReader = CheckedReader(buffer)
    while consumed != consumable do
      pushByte(builder.id, checkedReader.read_int8())
      consumed += 1

    ParseResult(
      nextState = if builder.id.length == 4 then State.HeaderLast3() else State.HeaderID(),
      remaining = buffer.slice(consumed, buffer.length)
    )

  private def parseLast3(buffer: Array[Byte]) : ParseResult =
    val consumable = min(buffer.length, 3 - builder.last3.length)
    var consumed = 0
    val checkedReader = CheckedReader(buffer)
    while consumed != consumable do
      pushByte(builder.last3, checkedReader.read_int8())
      consumed += 1

    ParseResult(
      nextState = if builder.last3.length == 3 then State.Body() else State.HeaderLast3(),
      remaining = buffer.slice(consumable, buffer.length)
    )
  
  private def parseBody(buffer: Array[Byte]) : ParseResult =
    val consumable = min(buffer.length, builder.readLength() - builder.length.length)
    var consumed = 0
    val checkedReader = CheckedReader(buffer)
    while consumed != consumable do
      pushByte(builder.body, checkedReader.read_int8())
      consumed += 1

    ParseResult(
      nextState = if builder.body.length + HEADER_LENGTH == builder.readLength() then State.Done() else State.Body(),
      remaining = buffer.slice(consumed, buffer.length)
    )

  private def unreachable[T] : T = throw RuntimeException("unreachable")

  private def parse(buffer: Array[Byte]) : ParseResult =
    state match
      case _ : State.HeaderLength => parseLength(buffer)
      case _ : State.HeaderID => parseId(buffer)
      case _ : State.HeaderLast3 => parseLast3(buffer)
      case _ : State.Body => parseBody(buffer)
      case _ : State.Done => unreachable

  @tailrec
  private def consume(buffer: Array[Byte], completed: List[rawpacket.FinishedPacket]) : List[rawpacket.FinishedPacket] =
    if buffer.length == 0
    then return completed

    parse(buffer) match
      case ParseResult(State.Done(), remaining) =>
        state = startState
        consume(remaining, completed :+ builder.toFinishedPacket())
      case ParseResult(nextState, remaining) =>
        state = nextState
        consume(remaining, completed)

  def consume(buffer: Array[Byte]) : List[rawpacket.FinishedPacket] = consume(buffer, List())
}

class JdwpPacket {}
class JdwpCommandHeader{}
class JdwpReplyHeader(val length: Int, val id: Int, val flags: Byte, val errorCode: Short){}

object CommandPacket {
  def toWire(id: Int, command: JdwpCommand & BodyToWire) : Array[Byte] =
    val body = command.bodyToWire()
    val b_id = ByteWrangler.int32_to_beI32(id);
    val b_length = ByteWrangler.int32_to_beI32(body.length + 11);
    val commandSetID = command.command.commandSetID
    val commandID = command.command.commandID
    val header = Array[Byte](
      b_length(0),
      b_length(1),
      b_length(2),
      b_length(3),
      b_id(0),
      b_id(1),
      b_id(2),
      b_id(3),
      0,
      commandSetID,
      commandID
    )
    header ++ body
}

object ReplyPacket {
  def fromWire(bytes: Array[Byte]) : Array[Byte] =
    val checkedReader = CheckedReader(bytes)
    val header = readHeader(checkedReader)
    new Array[Byte](0)

  def readHeader(reader: CheckedReader) : JdwpReplyHeader =
    JdwpReplyHeader(
      length = reader.read_int32(),
      id = reader.read_int32(),
      flags = reader.read_int8(),
      errorCode = reader.read_int16(),
    )

  def toWire(id: Int, reply: BodyToWire) : Array[Byte] =
    val body = reply.bodyToWire()
    val b_length = ByteWrangler.int32_to_beI32(body.length + 11);
    val b_id = ByteWrangler.int32_to_beI32(id);
    val flags : Byte = 0x80.asInstanceOf[Byte]
    val b_errorCode = ByteWrangler.int16_to_beI16(0)
    val header = Array[Byte](
      b_length(0),
      b_length(1),
      b_length(2),
      b_length(3),
      b_id(0),
      b_id(1),
      b_id(2),
      b_id(3),
      flags,
      b_errorCode(0),
      b_errorCode(1)
    )
    header ++ body
}

enum Command(val commandSetID: Byte, val commandID: Byte):
  case VirtualMachine_IDSizes extends Command(Command.VIRTUAL_MACHINE, 7)
  
  case EventRequest_Set extends Command(Command.EVENT_REQUEST, 1)
  case EventRequest_Clear extends Command(Command.EVENT_REQUEST, 2)
  case EventRequest_ClearAllBreakpoints extends Command(Command.EVENT_REQUEST, 3)
  
  case Event_Composite extends Command(Command.EVENT, 100)

object Command {
  import scala.collection.immutable.HashMap
  type CommandParser = FromWire[JdwpCommand]

  def maybeGetParser(commandSet: Byte, command: Byte) : Option[CommandParser] =
    parsersByCmdByCmdSet
      .get(commandSet)
      .flatMap(parsersByCmd => parsersByCmd.get(command))

  def maybeGetParser(cmd: Command) : Option[CommandParser] =
    maybeGetParser(cmd.commandSetID, cmd.commandID)

  def maybeGetParser(packet: rawpacket.Command) : Option[CommandParser] =
    maybeGetParser(packet.commandSet, packet.command)

  final val VIRTUAL_MACHINE : Byte = 1
  final val EVENT_REQUEST : Byte = 15
  final val EVENT : Byte = 64
  final val parsersByCmdByCmdSet : HashMap[Byte, HashMap[Byte, CommandParser]] = HashMap(
    (
      VIRTUAL_MACHINE,
      HashMap(
        (VirtualMachine_IDSizes.commandID, packet.command.IdSizes)
      )
    ),
    (
      EVENT_REQUEST,
      HashMap(
        (EventRequest_Set.commandID, packet.command.event_request.Set)
      )
    ),
    (
      EVENT,
      HashMap(
        (Event_Composite.commandID, packet.command.event.Composite)
      )
    )
  )
}

trait JdwpCommand {
  val command: Command
}

trait BodyToWire {
  def bodyToWire() : Array[Byte]
}

trait FromWire[+T] {
  // TODO: __body__fromWire
  def fromWire(idSizes: packet.reply.IdSizes, buffer: Array[Byte]): T // TODO: should be Option[T]
}

package packet.command:
  class IdSizes() extends JdwpCommand with BodyToWire {
    final val command = Command.VirtualMachine_IDSizes
    def bodyToWire() = new Array[Byte](0)
  }

  object IdSizes extends FromWire[JdwpCommand] {
    def fromWire(idSizes: packet.reply.IdSizes, body: Array[Byte]) : IdSizes = IdSizes()
  }

package packet.reply:
  class IdSizes(
    val fieldIDSize: Int,
    val methodIDSize: Int,
    val objectIDSize: Int,
    val referenceTypeIDSize: Int,
    val frameIDSize: Int
  ) {}

  object IdSizes extends FromWire[IdSizes] {
    /**
     * During VM connect, we don't know IdSizes, so we ask for it,
     * but we need an IdSizes to to call `fromWire` to parse the IdSizes reply packet.
     * This is a placeholder for that situation.
     */
    def dummy : IdSizes = IdSizes(-1,-1,-1,-1,-1)

    def fromWire(idSizes: IdSizes, body: Array[Byte]) : IdSizes =
      val checkedReader = CheckedReader(body)
      IdSizes(
        fieldIDSize = checkedReader.read_int32(),
        methodIDSize = checkedReader.read_int32(),
        objectIDSize = checkedReader.read_int32(),
        referenceTypeIDSize = checkedReader.read_int32(),
        frameIDSize = checkedReader.read_int32(),
      )
  }

package packet.reply.event_request:
  class Set(val requestID: Int) {}
  object Set extends FromWire[Set] {
    def fromWire(idSizes: IdSizes, body: Array[Byte]) : Set =
      val reader = JdwpSizedReader(idSizes, body)
      Set(requestID = reader.read_int32())
  }

package packet.command.event_request:
  object EventRequest {
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

  class Set(
    val eventKind: Byte,
    val suspendPolicy: Byte,
    val modifiers: Seq[Set.Modifier]
  ) extends JdwpCommand {
    val command = Command.VirtualMachine_IDSizes
    def hasCount() = modifiers.exists(_ match
      // what if some client sends zero or a negative value?
      case Set.Modifier.Count(c) if c > 0 => true
      case _ => false
    )
  }
  
  object Set extends FromWire[Set] {
    import EventRequest._
    enum Modifier:
      case Count(count: Int)
      case Conditional(exprID: Int)
      case ThreadOnly(thread: Long)
      case ClassOnly(clazz: Long)
      case ClassMatch(classPattern: String)
      case ClassExclude(classPattern: String)
      case LocationOnly(loc: Location)
      case ExceptionOnly(exceptionOrNull: Long, caught: Boolean, uncaught: Boolean)
      case FieldOnly(declaringClazz: Long, fieldID: Long)
      case Step(thread: Long, size: Int, depth: Int)
      case InstanceOnly(instance: Long)
      case SourceNameMatch(sourceNamePattern: String)

    def fromWire(idSizes: reply.IdSizes, buffer: Array[Byte]): Set =
      import Modifier._
      val reader = JdwpSizedReader(idSizes, buffer)
      val eventKind = reader.read_int8()
      val suspendPolicy = reader.read_int8()
      val modifiersCount = reader.read_int32()
      val modifiers = (0 until modifiersCount).map(_ => {
        val modKind = reader.read_int8()
        modKind match
          case COUNT => Count(reader.read_int32())
          case CONDITIONAL => Conditional(reader.read_int32())
          case THREAD_ONLY => ThreadOnly(reader.readThreadID())
          case CLASS_ONLY => ClassOnly(reader.readReferenceTypeID())
          case CLASS_MATCH => ClassMatch(reader.readString())
          case CLASS_EXCLUDE => ClassExclude(reader.readString())
          case LOCATION_ONLY => LocationOnly(reader.readLocation())
          case EXCEPTION_ONLY => ExceptionOnly(reader.readReferenceTypeID(), reader.readBoolean(), reader.readBoolean())
          case FIELD_ONLY => FieldOnly(reader.readReferenceTypeID(), reader.readFieldID())
          case STEP => Step(reader.readThreadID(), reader.read_int32(), reader.read_int32())
          case INSTANCE_ONLY => InstanceOnly(reader.readObjectID())
          case SOURCE_NAME_MATCH => SourceNameMatch(reader.readString())
          case _ => throw new RuntimeException(s"Unexpected modkind '${modKind}'")
      })
      Set(eventKind, suspendPolicy, modifiers)
  }

package packet.command.event:
  enum Event(val requestID: Int):
    case VMStart(requestID: Int, thread: Long)
      extends Event(requestID)
    case VMDeath(requestID: Int)
      extends Event(requestID)
    case SingleStep(requestID: Int, thread: Long, location: Location)
      extends Event(requestID)
    case Breakpoint(requestID: Int, thread: Long, location: Location)
      extends Event(requestID)
    case MethodEntry(requestID: Int, thread: Long, location: Location)
      extends Event(requestID)
    case MethodExit(requestID: Int, thread: Long, location: Location)
      extends Event(requestID)
    case MethodExitWithReturnValue(requestID: Int, thread: Long, location: Location, value: Value)
      extends Event(requestID)
    case MonitorContendedEnter(requestID: Int, thread: Long, obj: TaggedObjectID, location: Location)
      extends Event(requestID)
    case MonitorContendedEntered(requestID: Int, thread: Long, obj: TaggedObjectID, location: Location)
      extends Event(requestID)
    case MonitorWait(requestID: Int, thread: Long, obj: TaggedObjectID, location: Location, timeout: Long)
      extends Event(requestID)
    case MonitorWaited(requestID: Int, thread: Long, obj: TaggedObjectID, location: Location, timed_out: Boolean)
      extends Event(requestID)
    case Exception(requestID: Int, thread: Long, location: Location, exception: TaggedObjectID, catchLocation: Location)
      extends Event(requestID)
    case ThreadStart(requestID: Int, thread: Long)
      extends Event(requestID)
    case ThreadDeath(requestID: Int, thread: Long)
      extends Event(requestID)
    case ClassPrepare(requestID: Int, thread: Long, refTypeTag: Byte, refTypeID: Long, signature: String, status: Int)
      extends Event(requestID)
    case ClassUnload(requestID: Int, signature: String)
      extends Event(requestID)
    case FieldAccess(requestID: Int, thread: Long, location: Location, refTypeTag: Byte, refTypeID: Long, fieldID: Long, obj: TaggedObjectID)
      extends Event(requestID)
    case FieldModification(requestID: Int, thread: Long, location: Location, refTypeTag: Byte, refTypeID: Long, fieldID: Long, obj: TaggedObjectID, valueToBe: Value)
      extends Event(requestID)
  
  class Composite(val suspendPolicy: Byte, val events: Seq[Event]) extends JdwpCommand {
    val command: Command = Command.Event_Composite
  }

  object Composite extends FromWire[Composite] {
    def fromWire(idSizes: IdSizes, buffer: Array[Byte]): Composite =
      import Event._
      import EventKind._
      val reader = JdwpSizedReader(idSizes, buffer)
      val suspendPolicy = reader.read_int8()
      val eventCount = reader.read_int32()
      val events = (0 until eventCount).map(_ => {
        val eventKind = reader.read_int8()
        val requestID = reader.read_int32()
        eventKind match
          case VM_START => VMStart(requestID, reader.readThreadID())
          case SINGLE_STEP => SingleStep(requestID, reader.readThreadID(), reader.readLocation())
          case BREAKPOINT => Breakpoint(requestID, reader.readThreadID(), reader.readLocation())
          case METHOD_ENTRY => MethodEntry(requestID, reader.readThreadID(), reader.readLocation())
          case METHOD_EXIT => MethodExit(requestID, reader.readThreadID(), reader.readLocation())
          case METHOD_EXIT_WITH_RETURN_VALUE => MethodExitWithReturnValue(requestID, reader.readThreadID(), reader.readLocation(), reader.readValue())
          case MONITOR_CONTENDED_ENTER => MonitorContendedEnter(requestID, reader.readThreadID(), reader.readTaggedObjectID(), reader.readLocation())
          case MONITOR_WAIT => MonitorWait(requestID, reader.readThreadID(), reader.readTaggedObjectID(), reader.readLocation(), reader.read_int64())
          case MONITOR_WAITED => MonitorWaited(requestID, reader.readThreadID(), reader.readTaggedObjectID(), reader.readLocation(), reader.readBoolean())
          case EXCEPTION => Exception(requestID, reader.readThreadID(), reader.readLocation(), reader.readTaggedObjectID(), reader.readLocation())
          case THREAD_START => ThreadStart(requestID, reader.readThreadID())
          case THREAD_DEATH => ThreadDeath(requestID, reader.readThreadID())
          case CLASS_PREPARE => ClassPrepare(requestID, reader.readThreadID(), reader.read_int8(), reader.readReferenceTypeID(), reader.readString(), reader.read_int32())
          case CLASS_UNLOAD => ClassUnload(requestID, reader.readString())
          case FIELD_ACCESS => FieldAccess(requestID, reader.readThreadID(), reader.readLocation(), reader.read_int8(), reader.readReferenceTypeID(), reader.readFieldID(), reader.readTaggedObjectID())
          case FIELD_MODIFICATION => FieldModification(requestID, reader.readThreadID(), reader.readLocation(), reader.read_int8(), reader.readReferenceTypeID(), reader.readFieldID(), reader.readTaggedObjectID(), reader.readValue())
          case VM_DEATH => VMDeath(requestID)
          case _ => throw new RuntimeException(s"unexpected eventKind '${eventKind}'")
      })
      Composite(suspendPolicy, events)
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
