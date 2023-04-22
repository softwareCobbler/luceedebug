package dwr.jdwp_proxy

import dwr.utils._
import dwr.reader._
import dwr.raw_packet as raw
import dwr.jdwp.{EventKind}
import dwr.jdwp.packet.command
import dwr.jdwp.packet.reply
import dwr.jdwp.packet._

import java.util.concurrent.atomic.AtomicInteger
import java.nio.charset.StandardCharsets
import java.io.IOException
import java.net.Socket
import java.net.InetSocketAddress

import scala.collection.mutable.ArrayBuffer
import scala.util.control.Breaks._

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
  val receiveFromClient: (ClientFacingProxyClient, raw.FinishedPacket) => Unit,
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

  type OnCommandReplyCallback = raw.Reply => Unit
  type OnEventReceiptCallback = (cmd: raw.Command, requestID: Int) => Unit
  
  private object OnCommandReplyStrategy {
    def recordEventRequestAndForward(
      origin: PacketOrigin,
      onEventReceiptStrategy: OnEventReceiptCallback,
      request: command.event_request.Set
    ) : OnCommandReplyCallback =
      rawPacket => {
        // parse
        val reply_ = reply.event_request.Set.fromWire(idSizes, rawPacket.raw)
  
        // record
        activeEventRequests_.addOne((reply_.requestID, onEventReceiptStrategy))
        if request.eventKind == EventKind.BREAKPOINT
        then bpEventRequestsByClient_
          .getOrElseUpdate(origin.client.id, TrieMap[Int, Unit]())
          .addOne((reply_.requestID, ()))
  
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
  
  object OnEventReceiptStrategy {
    def forwardAndDiscard(client: ClientFacingProxyClient) : OnEventReceiptCallback =
      (rawPacket, requestID) => {
        client.receiveFromJVM(rawPacket.raw)
        clearEventRequest(client, requestID)
      }

    def justForward(client: ClientFacingProxyClient) : OnEventReceiptCallback =
      (rawPacket, requestID) => client.receiveFromJVM(rawPacket.raw)
  }

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
            case rawPacket : raw.Command =>
              Command
                .maybeGetParser(rawPacket)
                .map(_.fromWire(idSizes, rawPacket.raw)) match
                  case Some(compositeEvent : command.event.Composite) =>
                    for (event <- compositeEvent.events) do
                      if event.requestID == 0
                      then () // should send to all clients
                      else
                        activeEventRequests_.get(event.requestID) match
                          case Some(callback) =>
                            // need to rebuild the packet to be a composite event of 1 event
                            callback(rawPacket, event.requestID)
                          case None => throw new RuntimeException(s"Event with non-zero requestID=${event.requestID} recieved, but no callback is available.")
                  case _ => throw new RuntimeException(s"Unexpected command packet from JVM, commandSet=${rawPacket.commandSet} command=${rawPacket.command}")
              for ((_, client) <- clients_) do
                // split into event id streams ...
                client.receiveFromJVM(rawPacket.raw)
            case rawPacket : raw.Reply =>
              commandsAwaitingReply_.get(rawPacket.ID) match
                case Some(callback) =>
                  callback(rawPacket)
                  commandsAwaitingReply_.remove(rawPacket.ID)
                case None => throw new RuntimeException(s"Reply packet from JVM having id=${rawPacket.ID} was unexpected; no handler is available.")
      },
      receiveFromClient = (client, rawPacket) => {
        rawPacket match
          case rawCmd : raw.Command =>
            Command
              .maybeGetParser(rawCmd)
              .map(_.fromWire(idSizes, rawCmd.data)) match
                case Some(cmd : command.event_request.ClearAllBreakpoints) =>
                  final class W(val bpRequestID: Int, val freshPacketID: Int, val bytes: Array[Byte])
                  val ws = bpEventRequestsByClient_
                    .getOrElseUpdate(client.id, TrieMap())
                    .keys
                    .map(bpRequestID => {
                      val freshPacketID = nextPacketID.getAndIncrement()
                      val bytes = ReplyPacket.toWire(nextPacketID.getAndIncrement(), command.event_request.Clear(EventKind.BREAKPOINT, bpRequestID))
                      W(bpRequestID, freshPacketID, bytes)
                    })
                    .toSeq

                  if ws.length > 0
                  then
                    val onReplyStrategy = OnCommandReplyStrategy.doAfterNResponses(
                      ws.length,
                      proxyClient => {
                        ws.foreach(w => clearEventRequest(client, w.bpRequestID))
                        val synthesizedResponse =  ReplyPacket.toWire(rawPacket.ID, reply.event_request.ClearAllBreakpoints())
                        client.receiveFromJVM(synthesizedResponse)
                      }
                    )

                    ws.foreach(w => {
                      commandsAwaitingReply_.addOne((w.freshPacketID, onReplyStrategy))
                      jvmSocketIO.write(w.bytes, 1000)
                    })
                case Some(cmd : command.event_request.Clear) =>
                  clearEventRequest(client, cmd.requestID)
                  
                  val onReplyStrategy = OnCommandReplyStrategy.justForward(PacketOrigin(rawPacket.ID, client))
                  
                  val newID = nextPacketID.getAndIncrement()
                  
                  rawCmd.withSwappedID(
                    newID,
                    packet => {
                      commandsAwaitingReply_.addOne((newID, onReplyStrategy))
                      jvmSocketIO.write(packet.raw, 1000)
                    }
                  )
                case Some(cmd : command.event_request.Set) =>
                  val eventReceiptStrategy = if cmd.hasCount()
                    then OnEventReceiptStrategy.forwardAndDiscard(client)
                    else OnEventReceiptStrategy.justForward(client)
                  
                  val onReplyStrategy = OnCommandReplyStrategy
                    .recordEventRequestAndForward(
                      PacketOrigin(rawPacket.ID, client),
                      eventReceiptStrategy,
                      cmd
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
                  throw new RuntimeException(s"unhandled command received from client '${client.name}', commandSet=${rawCmd.commandSet} command=${rawCmd.command}")
          case reply : raw.Reply =>
            throw new RuntimeException(s"unexpected reply packet from client '${client.name}'")
      }
    )
  }

  private val clients_ = TrieMap[Int, ClientFacingProxyClient]()
  private val commandsAwaitingReply_ = TrieMap[Int, OnCommandReplyCallback]()
  private val activeEventRequests_ = TrieMap[Int, OnEventReceiptCallback]()
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
    val parser = raw.PacketParser()
    val client : ClientFacingProxyClient = ClientFacingProxyClient(
      id = nextClientID.getAndIncrement(),
      io = socketIO,
      // give the jvm proxy client completed packets
      receiveFromClient = (client, bytes) => {
        for (packet <- parser.consume(bytes)) do
          jvm.receiveFromClient(client, packet)
      },
      // although we give the jvm completed packets, the jvm gives us raw bytes,
      // and we just forward them
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
  private def getSocketIOFromHandshake(host: String, port: Int) : (SocketIO, raw.PacketParser, reply.virtual_machine.IdSizes) =
    val socket = new Socket()
    val inetAddr = new InetSocketAddress(host, port);
    socket.connect(inetAddr, /*ms*/5000);
    val socketIO = SocketIO(socket.getInputStream(), socket.getOutputStream())
    
    socketIO.write(HANDSHAKE_BYTES, 1000)
    val handshakeIn = socketIO.read(HANDSHAKE_STRING.length, 5000)
    val receivedHandshake = String(CheckedReader(handshakeIn).readN(HANDSHAKE_STRING.length), StandardCharsets.UTF_8)

    if !receivedHandshake.equals(HANDSHAKE_STRING)
    then throw new IOException(s"Bad JDWP handshake, got '${receivedHandshake}'")

    socketIO.write(
      bytes = CommandPacket.toWire(0, command.virtual_machine.IdSizes()),
      timeout_ms = 5000
    )
    
    val parser = raw.PacketParser()
    val parsed = ArrayBuffer[raw.FinishedPacket]()
    val chunkedReader = socketIO.chunkedReader(128)
    
    //
    // Read until we get the IdSizes reply, which we require to meaningfully parse packets.
    //
    var idSizes : Option[reply.virtual_machine.IdSizes] = None
    breakable {
      while true do
        for (packet <- parser.consume(chunkedReader())) do
          if packet.isInstanceOf[raw.Reply] && packet.ID == 0
          then
            idSizes = Some(reply.virtual_machine.IdSizes.fromWire(IdSizes.dummy, packet.data))
            break
          else parsed += packet
    }

    (socketIO, parser, idSizes.get)
}

object CompositeEvent {
  extension (v: command.event.Composite) {
    def copyFromSingleEvent(event: command.event.Event) : command.event.Composite =
      command.event.Composite(v.suspendPolicy, Seq(event))
  }
}
