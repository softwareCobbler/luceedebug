package luceedebug.jdwp_proxy

import luceedebug.jdwp_proxy.JdwpProxy.{HANDSHAKE_BYTES, HANDSHAKE_STRING}

import java.io.{IOException, InputStream, OutputStream}
import java.net.{InetAddress, InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}
import scala.annotation.tailrec
import scala.collection.mutable.HashMap
import java.util.concurrent.atomic.AtomicInteger
import luceedebug.jdwp_proxy.JdwpProxy.IProxyClient
import java.net.ServerSocket
import luceedebug.jdwp_proxy.ISocketIO.chunkedReader

enum Foo:
  case X(v: Any)
  case Y()

@main
def ok() : Unit =
  val x = new Array[Byte](5)
  println(x.slice(0,45)(5));
  //JdwpProxy().connect("localhost", 9999)//println(CheckedReader("xpabx".getBytes(StandardCharsets.UTF_8)).readUtf8("xpab"))


abstract class ISocketIO {
  def chunkedReader(chunkSize: Int) : () => Array[Byte]
  def write(bytes: Array[Byte], timeout_ms: Int) : Unit
  def read(n: Int, timeout_ms: Int) : Unit
}

class SocketIO(private val inStream: InputStream, private val outStream: OutputStream) extends ISocketIO {
  private implicit val executionContext : ExecutionContext = ExecutionContext.fromExecutor(
    Executors.newFixedThreadPool(3)
  )

  def chunkedReader(chunkSize: Int) = {
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

// class Span(private val buffer: Array[Byte], private val offset: Int, private val xlength: Int)
// extends Iterator[Byte]
// {
//     if offset + xlength > buffer.length then
//       throw new RuntimeException(s"Out of bounds span (bufferSize=${buffer.length}, offset=${offset}, length=${xlength})")
//     if offset < 0 then
//       throw new RuntimeException("Illegal negative offset for Span")
//     if xlength < 0 then
//       throw new RuntimeException("Illegal negative length for Span")

//     private var index : Int = 0

//     def apply(i: Int) : Byte =
//       if i >= xlength then throw new RuntimeException(s"Out of bounds apply access (bufferSize=${buffer.length}, offset=${offset}, length=${xlength}, i=${i})")
//       buffer(offset + i)

//     def hasNext() : Boolean = index < xlength
//     def next() : Byte =
//       val b = buffer(offset + index)
//       index += 1
//       b

//     def toByteArray: Array[Byte] = buffer.slice(offset, offset + xlength)
//     // could start in the middle of a multibyte codepoint ...
//     def toStringFromUtf8 : String = String(toByteArray, StandardCharsets.UTF_8)
// }

// java is big endian, network is big endian
class CheckedReader(raw: Array[Byte]) {
  private var index : Int = 0

  private def getAndAdvance(len: Int) : Array[Byte] =
    if index + len >= raw.length
    then throw new RuntimeException("out of bounds buffer access")
    val ret = raw.slice(index, index + len)
    index += len
    ret

  def readN(n: Int) : Array[Byte] = getAndAdvance(n)

  def read_int8: Byte =
    val span = getAndAdvance(1)
    span(0)

  def read_int16: Short =
    val span = getAndAdvance(2)
    val ret =
      (span(0) << 8)
        | (span(1) << 0)
    ret.asInstanceOf[Short]

  def read_int32 : Int =
    val span = getAndAdvance(4)
    (span(0) << 24)
      | (span(1) << 16)
      | (span(2) << 8)
      | (span(3) << 0)
}

class LuceeDebugJdwpProxy(
  jdwpHost: String,
  jdwpPort: Int,
  luceeClientHost: String,
  luceeClientPort: Int,
  jvmClientHost: String,
  jvmClientPort: Int
) {
  val proxyManager = JdwpProxyManager(jdwpHost, jdwpPort)

  enum ConnectionState:
    case Connected(client: ClientFacingProxyClient)
    case Listening(client: Future[ClientFacingProxyClient])
  import ConnectionState._
  
  // TODO: heed the warning, this isn't good to use for our use case
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  
  var lucee : ConnectionState = Listening({
    val future = Future { proxyManager.listenOn(luceeClientHost, luceeClientPort) }
    future.onComplete(v => {
      v match
        case Success(client) => { lucee = Connected(client) }
        case Failure(e) => throw e
    })
    future
  })

  var java : ConnectionState = Listening({
    val future = Future { proxyManager.listenOn(jvmClientHost, jvmClientPort) }
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

  private val proxy = new JdwpProxy(jvmHost, jvmPort)

  def listenOn(host: String, port: Int) : ClientFacingProxyClient =
    val socket = {
      val socket = new ServerSocket()
      val inetAddr = new InetSocketAddress(host, port);
      socket.bind(inetAddr)
      socket.accept()
    }
    proxy.createAndRegisterClient(socket)

}

class ClientFacingProxyClient(
  val id: Int,
  val io: ISocketIO,
  val receiveFromClient: Array[Byte] => Unit,
  val receiveFromJVM: Array[Byte] => Unit,
) {
  private val thread = {
    val thread = new Thread(() => {
      val reader = io.chunkedReader(1024)
      while true do
        receiveFromClient(reader())
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
  private val jvmSocketIO = JdwpProxy.getSocketIOFromHandshake(host, port)
  private val jvm : JvmFacingProxyClient = {
    val parser = PacketParser()
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
  ) : ClientFacingProxyClient =
    val socketIO = SocketIO(clientSocket.getInputStream(), clientSocket.getOutputStream())
    val parser = PacketParser()
    val client : ClientFacingProxyClient = ClientFacingProxyClient(
      id = nextClientID.getAndIncrement(),
      io = socketIO,
      receiveFromClient = bytes => {
        for (packet <- parser.consume(bytes)) do
          jvm.receiveFromClient(client, packet)
      },
      receiveFromJVM = bytes => socketIO.write(bytes, /*ms*/5000)
    )
    clients_.put(client.id, client)
    client

  def unregisterClient(client: ClientFacingProxyClient) : Unit = clients_.remove(client.id)
}

object JdwpProxy {
  private val HANDSHAKE_STRING: String = "JDWP-Handshake"
  private val HANDSHAKE_BYTES: Array[Byte] = HANDSHAKE_STRING.getBytes(StandardCharsets.US_ASCII)
  
  @throws(classOf[IOException])
  private def getSocketIOFromHandshake(host: String, port: Int) : SocketIO =
    val socket = new Socket()
    val inetAddr = new InetSocketAddress(host, port);
    socket.connect(inetAddr, /*ms*/5000);
    val socketIO = SocketIO(socket.getInputStream(), socket.getOutputStream())
    
    socketIO.write(HANDSHAKE_BYTES, 1000)
    val handshakeIn = socketIO.read(HANDSHAKE_STRING.length, 5000)
    val receivedHandshake = String(CheckedReader(handshakeIn).readN(HANDSHAKE_STRING.length), StandardCharsets.UTF_8)

    // do the "size" message first?

    if receivedHandshake.equals(HANDSHAKE_STRING)
    then socketIO
    else throw new IOException(s"Bad JDWP handshake, got '${receivedHandshake}'")
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
    var remaining = 4 - builder.length.length
    val checkedReader = CheckedReader(buffer)
    while remaining > 0 do
      pushByte(builder.length, checkedReader.read_int8)
      remaining -= 1

    ParseResult(
      nextState = if builder.length.length == 4 then State.HeaderID() else State.HeaderLength(),
      remaining = buffer.slice(remaining, buffer.length)
    )

  private def parseId(buffer: Array[Byte]) : ParseResult =
    var remaining = 4 - builder.id.length
    val checkedReader = CheckedReader(buffer)
    while remaining > 0 do
      pushByte(builder.id, checkedReader.read_int8)
      remaining -= 1

    ParseResult(
      nextState = if builder.id.length == 4 then State.HeaderLast3() else State.HeaderID(),
      remaining = buffer.slice(remaining, buffer.length)
    )

  private def parseLast3(buffer: Array[Byte]) : ParseResult =
    var remaining = 3 - builder.last3.length
    val checkedReader = CheckedReader(buffer)
    while remaining > 0 do
      pushByte(builder.last3, checkedReader.read_int8)
      remaining -= 1

    ParseResult(
      nextState = if builder.last3.length == 3 then State.Body() else State.HeaderLast3(),
      remaining = buffer.slice(remaining, buffer.length)
    )
  
  private def parseBody(buffer: Array[Byte]) : ParseResult =
    var remaining = builder.readLength() - builder.length.length
    val checkedReader = CheckedReader(buffer)
    while remaining > 0 do
      pushByte(builder.body, checkedReader.read_int8)
      remaining -= 1

    ParseResult(
      nextState = if builder.body.length == builder.readLength() then State.Done() else State.Body(),
      remaining = buffer.slice(remaining, buffer.length)
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
    parse(buffer) match
      case ParseResult(State.Done(), remaining) =>
        state = startState
        consume(remaining, completed :+ builder.toFinishedPacket())
      case ParseResult(nextState, remaining) =>
        state = nextState
        completed

  def consume(buffer: Array[Byte]) : List[rawpacket.FinishedPacket] = consume(buffer, List())
}

class JdwpPacket {}
class JdwpCommandHeader{}
class JdwpReplyHeader(val length: Int, val id: Int, val flags: Byte, val errorCode: Short){}

object CommandPacket {
  def toWire(id: Int, flags: Int, command: JdwpCommand) : Array[Byte] =
    val body = command.toWire
    val length = body.length + 11;
    val flags = 0
    val commandSetID = command.command.getCommentSetID
    val commandID = command.command.getCommandID
    val header = new Array[Byte](0)
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

enum Command(commandSetID: Int, commandID: Int):
  def getCommentSetID = commandSetID
  def getCommandID = commandID
  case VirtualMachine_IDSizes extends Command(1, 7)

trait JdwpCommand {
  val command: Command
  def toWire : Array[Byte]
}

trait FromWire[T] {
  def fromWire(buffer: Array[Byte]): T
}

package packet.out:
  class IdSizes() extends JdwpCommand {
    final val command = Command.VirtualMachine_IDSizes
    def toWire = new Array[Byte](0)
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
