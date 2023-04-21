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

enum Foo:
  case X(v: Any)
  case Y()

@main
def ok() : Unit =
  JdwpProxy(host="localhost", port=9999)


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
    Await.result(Future { outStream.write(bytes) }, duration)

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
  def read_int8: Byte = ByteWrangler.beI8_to_int8(readN(1))
  def read_int16: Short = ByteWrangler.beI16_to_int16(readN(2))
  def read_int32 : Int = ByteWrangler.beI32_to_int32(readN(4))
}

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
}

class LuceeDebugJdwpProxy(
  jdwpHost: String,
  jdwpPort: Int,
  luceeClientHost: String,
  luceeClientPort: Int,
  jvmClientHost: String,
  jvmClientPort: Int
) {
  private val proxy = new JdwpProxy(jdwpHost, jdwpPort)

  enum ConnectionState:
    case Connected(client: ClientFacingProxyClient)
    case Listening(client: Future[ClientFacingProxyClient])
  import ConnectionState._

  private def listenOn(host: String, port: Int) : Socket = 
    val socket = new ServerSocket()
    val inetAddr = new InetSocketAddress(host, port);
    socket.bind(inetAddr)
    socket.accept()
  
  // TODO: heed the warning, this isn't good to use for our use case
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  
  var lucee = connectLucee()
  var java = connectJava()
  
  private def connectLucee() : ConnectionState =
    Listening({
      val future = Future {
        val socket = listenOn(luceeClientHost, luceeClientPort)
        proxy.createAndRegisterClient(
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
        proxy.createAndRegisterClient(
          socket,
          () => connectJava()
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

class JdwpProxyManager(jvmHost: String, jvmPort: Int) {
  // jvm conn
  // listen for client conns, on ports ...
  // manage client conn state
  // receive client packet, forward to jvm
  // receive jvm packet, forward to client
}

class ClientFacingProxyClient(
  val id: Int,
  val io: ISocketIO,
  val receiveFromClient: (client: ClientFacingProxyClient, bytes: Array[Byte]) => Unit,
  val receiveFromJVM: Array[Byte] => Unit,
  val onClientDisconnect : () => Unit,
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

class JdwpProxy(host: String, port: Int) {
  class PacketOrigin(val originalID: Int, val client: ClientFacingProxyClient)

  private val nextClientID = AtomicInteger()
  private val (jvmSocketIO, parser, idSizes) = JdwpProxy.getSocketIOFromHandshake(host, port)
  private val jvm : JvmFacingProxyClient = {
    JvmFacingProxyClient(
      id = nextClientID.getAndIncrement(),
      io = jvmSocketIO,
      receiveFromJVM = bytes => {
        for (packet <- parser.consume(bytes)) do
          packet match
            case packet : rawpacket.Command =>
              for ((_, client) <- clients_) do
                client.receiveFromJVM(packet.raw)
            case packet : rawpacket.Reply =>
              commandsAwaitingReply_.get(packet.ID) match
                case Some(origin) => packet.withSwappedID(origin.originalID, packet => origin.client.receiveFromJVM(packet.raw))
                case None => () // ??
      },
      receiveFromClient = (client, packet) => {

      }
    )
  }

  private val clients_ = HashMap[Int, ClientFacingProxyClient]()
  private val commandsAwaitingReply_ = HashMap[Int, PacketOrigin]()

  def createAndRegisterClient(
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
      onClientDisconnect = onClientDisconnect
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
          if packet.ID == 0
          then
            idSizes = Some(IdSizes.fromWire(packet.raw))
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
          flags = last3Reader.read_int8,
          errorCode = last3Reader.read_int16,
          data = body.toArray,
          raw = raw.toArray
        )
      else
        val last3Reader = CheckedReader(last3.toArray)
        rawpacket.Command(
          length = readLength(),
          id = readID(),
          flags = last3Reader.read_int8,
          commandSet = last3Reader.read_int8,
          command = last3Reader.read_int8,
          data = body.toArray,
          raw = raw.toArray
        )
    
    def flags : Byte = last3(0)
    def isReply : Boolean = (flags & 0x80) > 0
    def isCommand : Boolean = !isReply

    def readLength() : Int = CheckedReader(length.toArray).read_int32
    def readID() : Int = CheckedReader(id.toArray).read_int32
  }

  sealed abstract class FinishedPacket {
    protected var id_ : Int;
    protected var raw_ : Array[Byte];

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
    val flags: Byte,
    val commandSet: Byte,
    val command: Byte,
    val data: Array[Byte],
    raw: Array[Byte]
  ) extends FinishedPacket {
    protected var id_ = id
    protected var raw_ = raw
  }
  class Reply(
    val length: Int,
    id: Int,
    val flags: Byte,
    val errorCode: Short,
    val data: Array[Byte],
    raw: Array[Byte]
  ) extends FinishedPacket {
    protected var id_ = id
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
      pushByte(builder.length, checkedReader.read_int8)
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
      pushByte(builder.id, checkedReader.read_int8)
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
      pushByte(builder.last3, checkedReader.read_int8)
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
      pushByte(builder.body, checkedReader.read_int8)
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
  def toWire(id: Int, command: JdwpCommand) : Array[Byte] =
    val body = command.bodyToWire
    val length = body.length + 11;
    val commandSetID = command.command.getCommandSetID
    val commandID = command.command.getCommandID
    val header = Array[Byte](
      (length & 0xFF000000 >> 24).asInstanceOf[Byte],
      (length & 0x00FF0000 >> 16).asInstanceOf[Byte],
      (length & 0x0000FF00 >> 8).asInstanceOf[Byte],
      (length & 0x000000FF >> 0).asInstanceOf[Byte],
      (id & 0xFF000000 >> 24).asInstanceOf[Byte],
      (id & 0x00FF0000 >> 16).asInstanceOf[Byte],
      (id & 0x0000FF00 >> 8).asInstanceOf[Byte],
      (id & 0x000000FF >> 0).asInstanceOf[Byte],
      0.asInstanceOf[Byte],
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
      length = reader.read_int32,
      id = reader.read_int32,
      flags = reader.read_int8,
      errorCode = reader.read_int16,
    )
}

enum Command(commandSetID: Byte, commandID: Byte):
  def getCommandSetID = commandSetID
  def getCommandID = commandID
  case VirtualMachine_IDSizes extends Command(1, 7)

trait JdwpCommand {
  val command: Command
  def bodyToWire : Array[Byte]
}

trait FromWire[T] {
  def fromWire(buffer: Array[Byte]): T
}

package packet.command:
  class IdSizes() extends JdwpCommand {
    final val command = Command.VirtualMachine_IDSizes
    def bodyToWire = new Array[Byte](0)
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
    def fromWire(buffer: Array[Byte]) : IdSizes =
      val checkedReader = CheckedReader(buffer)
      IdSizes(
        fieldIDSize = checkedReader.read_int32,
        methodIDSize = checkedReader.read_int32,
        objectIDSize = checkedReader.read_int32,
        referenceTypeIDSize = checkedReader.read_int32,
        frameIDSize = checkedReader.read_int32,
      )
  }
