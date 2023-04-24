package dwr.jdwp_proxy

import dwr.utils.*
import dwr.reader.*
import dwr.jdwp.packet.raw
import dwr.jdwp.*
import dwr.jdwp.packet.*
import dwr.jdwp.packet.command
import dwr.jdwp.packet.command.event.Event
import dwr.jdwp.packet.command.event.Event.{ThreadDeath, ThreadStart}
import dwr.jdwp.packet.reply
import dwr.packet_parser.PacketParser

import java.util.concurrent.atomic.AtomicInteger
import java.nio.charset.StandardCharsets
import java.io.IOException
import java.net.Socket
import java.net.InetSocketAddress
import scala.collection.mutable.ArrayBuffer
import scala.util.control.Breaks.*
import java.util.concurrent.LinkedBlockingDeque
import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap

final class EventRequestID(eventID: Int) extends AnyVal {
  def asInt: Int = eventID
};

trait IJdwpProxyConfig {
  val jvmSocketIO: ISocketIO;
  val idSizes: IdSizes;
  val state: IProxyState;
}

private final class JdwpProxyConfig(val jvmSocketIO: ISocketIO, val idSizes: IdSizes, val state: ProxyState) extends IJdwpProxyConfig {}

trait IProxyState {
  val knownThreads: TrieMap[ThreadID, Unit] = TrieMap()
  val clients: TrieMap[ClientID, ClientFacingProxyClient] = TrieMap()

  val commandsAwaitingReply: TrieMap[Int, OnCommandReplyCallback] = TrieMap()

  val allActiveEventRequests: TrieMap[EventRequestID, TrackedEvent] = TrieMap()
  val activeEventRequestsByClient: TrieMap[ClientID, TrieMap[EventRequestID, Unit]] = TrieMap()

  val bpEventRequestsByClient: TrieMap[ClientID, TrieMap[EventRequestID, Unit]] = TrieMap()
  val suspendCountByClientByThread: TrieMap[ClientID, TrieMap[ThreadID, AtomicInteger]] = TrieMap()
  val disabledObjectCollectionsByClient: TrieMap[ClientID, TrieMap[ObjectID, Unit]] = TrieMap()

  val clientIdGenerator: ClientIdGenerator = ClientIdGenerator()
  val packetIdGenerator: PacketIdGenerator = PacketIdGenerator()

  // bytes queued from the JVM, to be handled
  val inboundJvmBytes: LinkedBlockingDeque[Array[Byte]] = LinkedBlockingDeque()

  // bytes queued to send to the JVM
  val outboundJvmBytes: LinkedBlockingDeque[Array[Byte]] = LinkedBlockingDeque()

  val parser: PacketParser

  def untrackEventRequest(client: ClientFacingProxyClient, eventRequestID: EventRequestID): Unit =
    allActiveEventRequests.remove(eventRequestID)
    activeEventRequestsByClient.getOrElseUpdate(client.id, TrieMap()).remove(eventRequestID)
    bpEventRequestsByClient.get(client.id).foreach(bps => bps.remove(eventRequestID))
}

private final class ProxyState(val parser: PacketParser, override val packetIdGenerator: PacketIdGenerator) extends IProxyState {}

private type OnCommandReplyCallback = raw.Reply => Unit

private enum OnEventReceiptCallback:
  case Forward(cb: (bytes: Array[Byte], requestID: EventRequestID) => Unit)
  case Consume(cb: (event: Event) => Unit)

private enum TrackedEvent:
  case ClientRequestedEvent(
                             val eventKind: Byte,
                             val client: ClientFacingProxyClient,
                             val requestID: EventRequestID,
                             val onEventReceipt: OnEventReceiptCallback
                           )
  case ProxyRequestedEvent(
                            eventKind: Byte,
                            requestID: EventRequestID,
                            onEventReceipt: OnEventReceiptCallback.Consume
                          )

private class PacketOrigin(val originalID: Int, val client: ClientFacingProxyClient)

private object OnCommandReplyStrategy {
  def recordEventRequestAndForward(
                                    origin: PacketOrigin,
                                    onEventReceiptStrategy: OnEventReceiptCallback,
                                    request: command.event_request.Set
                                  )(using idSizes: IdSizes)(using state: IProxyState): OnCommandReplyCallback =
    rawPacket => {
      // parse
      val reply_ = reply.event_request.Set.bodyFromWire(rawPacket.body)

      // record
      println(s"Adding tracked event request ${reply_.requestID} for client ${origin.client.name}")
      state.allActiveEventRequests.addOne(
        EventRequestID(reply_.requestID),
        TrackedEvent.ClientRequestedEvent(request.eventKind, origin.client, EventRequestID(reply_.requestID), onEventReceiptStrategy)
      )
      state.activeEventRequestsByClient.getOrElseUpdate(origin.client.id, TrieMap()).put(EventRequestID(reply_.requestID), ())

      if request.eventKind == EventKind.BREAKPOINT
      then state.bpEventRequestsByClient
        .getOrElseUpdate(origin.client.id, TrieMap[EventRequestID, Unit]())
        .addOne((EventRequestID(reply_.requestID), ()))

      // forward
      rawPacket.withSwappedID(origin.originalID, mutatedPacket => origin.client.receiveFromJVM(mutatedPacket.raw))
    }

  def justForward(
                   origin: PacketOrigin
                 ): OnCommandReplyCallback =
    rawPacket => {
      rawPacket.withSwappedID(origin.originalID, mutatedPacket => origin.client.receiveFromJVM(mutatedPacket.raw))
    }

  def doAfterNResponses(n: Int, callback: OnCommandReplyCallback): OnCommandReplyCallback =
    var responses = AtomicInteger()
    rawPacket => {
      if responses.incrementAndGet() == n
      then callback(rawPacket)
      else ()
    }

  def sendSomeExactResponse(client: ClientFacingProxyClient, bytes: Array[Byte]): OnCommandReplyCallback =
    rawPacket => {
      client.receiveFromJVM(bytes)
    }

  def doNothing: OnCommandReplyCallback = rawPacket => ()
}

private object OnEventReceiptStrategy {
  def forwardAndDiscard(client: ClientFacingProxyClient)(using state: IProxyState): OnEventReceiptCallback =
    OnEventReceiptCallback.Forward(
      (bytes, requestID) => {
        client.receiveFromJVM(bytes)
        state.untrackEventRequest(client, requestID)
      }
    )

  def justForward(client: ClientFacingProxyClient): OnEventReceiptCallback =
    OnEventReceiptCallback.Forward(
      (bytes, requestID) => {
        if requestID.asInt != 4 // noisy debug requestID
        then println(s"forwarding event for requestID ${requestID.asInt} to client ${client.name}")
        client.receiveFromJVM(bytes)
      }
    )
}

class JdwpProxy(config: IJdwpProxyConfig) {

  import scala.collection.concurrent.TrieMap
  import OnCommandReplyStrategy._

  private val jvmSocketIO = config.jvmSocketIO
  private val state = config.state

  private final implicit val _idSizes_ : IdSizes = config.idSizes
  private final implicit val _state_ : IProxyState = state

  private val jvmWriteThread = new Thread(() => {
    while true do
      jvmSocketIO.write(state.outboundJvmBytes.take(), 5000)
  })

  private val jvmReadThread = new Thread(() => {
    while true do
      JvmPacketHandler.handleInboundJvmBytes(state)
  })

  def pumpPacketsUntilDisconnectBlocking(): Unit =
    jvmWriteThread.start()
    jvmReadThread.start()
    registerThreadTrackers()
    jvmProxyClient.pumpPacketsUntilDisconnectBlocking()

  private val jvmProxyClient = JvmFacingProxyClient(
    id = state.clientIdGenerator.next(),
    io = jvmSocketIO,
    receiveFromJVM = bytes => state.inboundJvmBytes.add(bytes),
    receiveFromClient = (client, rawPacket) => ClientPacketHandler.handleInboundClientPacket(client, rawPacket)
  )

  private def registerThreadTrackers(): Unit =
    val threadStartRequest = command.event_request.Set(EventKind.THREAD_START, SuspendPolicy.NONE, Seq())
    val threadEndRequest = command.event_request.Set(EventKind.THREAD_END, SuspendPolicy.NONE, Seq())

    val threadStartPacketID = state.packetIdGenerator.next()
    val threadEndPacketID = state.packetIdGenerator.next()

    val threadStartPacket = CommandPacket.toWire(threadStartPacketID, threadStartRequest)
    val threadEndPacket = CommandPacket.toWire(threadEndPacketID, threadEndRequest)

    state.commandsAwaitingReply.addOne(
      threadStartPacketID,
      rawReply => {
        val parsedReply = reply.event_request.Set.bodyFromWire(rawReply.body)
        state.allActiveEventRequests.addOne(
          EventRequestID(parsedReply.requestID),
          TrackedEvent.ProxyRequestedEvent(
            EventKind.THREAD_START,
            EventRequestID(parsedReply.requestID),
            OnEventReceiptCallback.Consume {
              case ThreadStart(_, threadID) =>
                state.knownThreads.put(threadID, ())
              case _ => throw new RuntimeException("Expected only `THREAD_START` events here")
            }
          )
        )
      }
    )

    state.commandsAwaitingReply.addOne(
      threadEndPacketID,
      rawReply => {
        val parsedReply = reply.event_request.Set.bodyFromWire(rawReply.body)
        state.allActiveEventRequests.addOne(
          EventRequestID(parsedReply.requestID),
          TrackedEvent.ProxyRequestedEvent(
            EventKind.THREAD_END,
            EventRequestID(parsedReply.requestID),
            OnEventReceiptCallback.Consume {
              case ThreadDeath(_, threadID) =>
                state.knownThreads.remove(threadID)
                state.suspendCountByClientByThread.foreach((_, suspendCountMap) => suspendCountMap.remove(threadID))
              case _ => throw new RuntimeException("Expected only `THREAD_DEATH` events here")
            }
          )
        )
      }
    )

    state.outboundJvmBytes.add(threadStartPacket)
    state.outboundJvmBytes.add(threadEndPacket)
  // todo: block, awaiting event receipts for the above

  def createAndRegisterClient(
                               name: String,
                               clientSocket: Socket,
                             ): ClientFacingProxyClient =
    val socketIO = SocketIO(clientSocket.getInputStream(), clientSocket.getOutputStream())

    println("reading handshake...")
    val handshakeIn = String(socketIO.read(JdwpProxy.HANDSHAKE_BYTES.length, 1000), StandardCharsets.UTF_8)
    println("read handshake...")

    if !handshakeIn.equals(JdwpProxy.HANDSHAKE_STRING)
    then throw new RuntimeException(s"Bad JDWP handshake for proxy client '${name}, handshake was '${handshakeIn}")

    println("read good handshake, writing ...")

    socketIO.write(JdwpProxy.HANDSHAKE_BYTES, 1000)
    println("wrote handshake, consuming packets...")

    val parser = PacketParser()
    val client = ClientFacingProxyClient(
      id = state.clientIdGenerator.next(),
      io = socketIO,
      // give the jvm proxy client completed packets
      receiveFromClient = (client, bytes) => {
        for (packet <- parser.consume(bytes)) do
          val length = packet.raw
          jvmProxyClient.receiveFromClient(client, packet)
      },
      // although we give the jvm completed packets, the jvm gives us raw bytes,
      // and we just forward them
      receiveFromJVM = bytes => socketIO.write(bytes, /*ms*/ 5000),
      name = name
    )
    if state.clients == null || client == null
    then
      println(s"no clients_ or client? clients_=${state.clients}, client=${client}")
      System.exit(1)

    state.clients.put(client.id, client)
    client
}

object JdwpProxy {
  final val HANDSHAKE_STRING: String = "JDWP-Handshake"
  final val HANDSHAKE_BYTES: Array[Byte] = HANDSHAKE_STRING.getBytes(StandardCharsets.US_ASCII)

  @throws(classOf[IOException])
  def initProxyConfig(host: String, port: Int): JdwpProxyConfig =
    val socket = new Socket()
    val inetAddr = new InetSocketAddress(host, port);
    socket.connect(inetAddr, /*ms*/ 5000);
    socket.setTcpNoDelay(true); // important for many tiny ~20 byte jdwp messages
    val socketIO = SocketIO(socket.getInputStream(), socket.getOutputStream())

    println("write handshake to jvm...")
    socketIO.write(HANDSHAKE_BYTES, 1000)
    println("read response handshake from jvm...")
    val handshakeIn = socketIO.read(HANDSHAKE_STRING.length, 5000)
    val receivedHandshake = String(CheckedReader(handshakeIn.view).readN(HANDSHAKE_STRING.length).toArray, StandardCharsets.UTF_8)
    println("got response handshake from jvm...")

    if !receivedHandshake.equals(HANDSHAKE_STRING)
    then throw new IOException(s"Bad JDWP handshake, got '${receivedHandshake}'")
    println("handshake from jvm ok...")

    val idSizesPacketID = 0
    socketIO.write(
      bytes = CommandPacket.toWire(idSizesPacketID, command.virtual_machine.IdSizes())(using IdSizes.dummy),
      timeout_ms = 5000
    )

    //
    // Read until we get the IdSizes reply, which we require to meaningfully parse all packets (except the IdSizes packet).
    //
    parseUntilIdSizesReceiptDiscardingOtherPackets(socketIO, idSizesPacketID, PacketParser())

  // we might not want to discard "other" packets, do clients want them, should we do anything with them?
  @tailrec
  private def parseUntilIdSizesReceiptDiscardingOtherPackets(socketIO: ISocketIO, idSizesPacketID: Int, parser: PacketParser): JdwpProxyConfig =
    parser.consume(socketIO.chunkedReader(128)().getOrFail()).collectFirst {
      case packet: raw.Reply if packet.ID == 0 => packet
    } match
      case Some(packet) =>
        val idSizes = reply.virtual_machine.IdSizes.bodyFromWire(packet.body)(using IdSizes.dummy)
        JdwpProxyConfig(socketIO, idSizes, ProxyState(parser, PacketIdGenerator(idSizesPacketID + 1)))
      case None => parseUntilIdSizesReceiptDiscardingOtherPackets(socketIO, idSizesPacketID, parser)


  def compositeEventFromSingleEvent(compositeEvent: command.event.Composite, event: command.event.Event): command.event.Composite =
    command.event.Composite(compositeEvent.suspendPolicy, Seq(event))
}

object JvmPacketHandler {

  import scala.collection.concurrent.TrieMap

  def recordSuspendedThreadsByClientForCompositeEvent(state: IProxyState, compositeEvent: command.event.Composite): Unit =
    compositeEvent.suspendPolicy match
      case SuspendPolicy.NONE => ()
      case SuspendPolicy.EVENT_THREAD =>
        for (event <- compositeEvent.events) do
          event.getThreadID() match
            case Some(threadID) =>
              state.allActiveEventRequests.get(EventRequestID(event.requestID)) match
                case Some(TrackedEvent.ClientRequestedEvent(_, client, _, _)) =>
                  state.suspendCountByClientByThread.getOrElseUpdate(client.id, TrieMap()).put(threadID, AtomicInteger(1))
                case _ => ()
            case None => ()
      case SuspendPolicy.ALL =>
        val uniqueClients = scala.collection.immutable.Set(
          compositeEvent
            .events
            .map(event => state.allActiveEventRequests.get(EventRequestID(event.requestID)))
            .collect({
              case Some(TrackedEvent.ClientRequestedEvent(_, client, _, _)) => client
            }) *
        )
        val suspendCountMap =
          if uniqueClients.size == 1
          then state.suspendCountByClientByThread.getOrElseUpdate(uniqueClients.head.id, TrieMap())
          else
          // ?? suspendCountByClientByThread.getOrElseUpdate(magicAllClientTokenClient.id, TrieMap())
            throw new RuntimeException("'SUSPEND_ALL' event set with more than 1 proxy client ref isn't handled")

        state.knownThreads.map((threadID, _) => {
          suspendCountMap.put(threadID, AtomicInteger(1))
        })
      case _ => throw new RuntimeException(s"Unexpected suspend policy ${compositeEvent.suspendPolicy}")

  // "Automatically generated events are sent with the requestID field in the Event Data set to 0."
  // An auto-generated event has no specific target client.
  private final val AUTO_GENERATED_EVENT: Byte = 0

  def runEventReceiptCallbacks(state: IProxyState, compositeEvent: command.event.Composite)(using idSizes: IdSizes): Unit =
    compositeEvent.events.foreach { event =>
      if event.requestID == AUTO_GENERATED_EVENT
      then
        state.clients.values.foreach { client =>
          client.receiveFromJVM(CommandPacket.toWire(state.packetIdGenerator.next(), JdwpProxy.compositeEventFromSingleEvent(compositeEvent, event)))
        }
      else
        state.allActiveEventRequests.get(EventRequestID(event.requestID)) match
          case Some(TrackedEvent.ClientRequestedEvent(_, client, requestID, onEventReceipt)) =>
            onEventReceipt match
              case OnEventReceiptCallback.Forward(cb) =>
                if !client.name.equals("lucee-frontend")
                then println(s"allActiveEventRequests found eventRequestID ${event.requestID} for client ${client.name}")

                cb(CommandPacket.toWire(state.packetIdGenerator.next(), JdwpProxy.compositeEventFromSingleEvent(compositeEvent, event)), requestID)
              case OnEventReceiptCallback.Consume(cb) =>
                cb(event)
          case Some(TrackedEvent.ProxyRequestedEvent(_, _, OnEventReceiptCallback.Consume(f))) => f(event)
          case None => throw new RuntimeException(s"Event with non-zero requestID=${event.requestID} recieved, but no callback is available; commandsAwaitingReply.size=${state.commandsAwaitingReply.size}")
    }

  def handleInboundJvmPackets(state: IProxyState, rawPackets: List[raw.FinishedPacket])(using idSizes: IdSizes): Unit =
    rawPackets.foreach {
      case rawCmd: raw.Command =>
        Command
          .maybeGetParser(rawCmd)
          .map(_.bodyFromWire(rawCmd.body)) match
          case Some(compositeEvent: command.event.Composite) =>
            recordSuspendedThreadsByClientForCompositeEvent(state, compositeEvent)
            runEventReceiptCallbacks(state, compositeEvent)
          case _ => throw new RuntimeException(s"Unexpected command packet from JVM, commandSet=${rawCmd.commandSet} command=${rawCmd.command}")
      case rawReply: raw.Reply =>
        state.commandsAwaitingReply.remove(rawReply.ID) match
          case Some(callback) => callback(rawReply)
          case None => throw new RuntimeException(s"Reply packet from JVM having id=${rawReply.ID} was unexpected; no handler is available.")
    }

  def handleInboundJvmBytes(state: IProxyState)(using idSizes: IdSizes): Unit =
    handleInboundJvmPackets(state, state.parser.consume(state.inboundJvmBytes.take()))
}

object ClientPacketHandler {
  def handleClearAllBreakpoints(client: ClientFacingProxyClient, state: IProxyState, rawPacket: raw.Command, cmd: command.event_request.ClearAllBreakpoints)(using idSizes: IdSizes): Unit =
    final class W(val bpRequestID: EventRequestID, val freshPacketID: Int, val bytes: Array[Byte])
    val ws = state.bpEventRequestsByClient
      .getOrElseUpdate(client.id, TrieMap())
      .keys
      .map(bpRequestID => {
        val freshPacketID = state.packetIdGenerator.next()
        val bytes = CommandPacket.toWire(state.packetIdGenerator.next(), command.event_request.Clear(EventKind.BREAKPOINT, bpRequestID.asInstanceOf[Int]))
        W(bpRequestID, freshPacketID, bytes)
      })
      .toSeq

    if ws.length > 0
    then
      val onReplyStrategy = OnCommandReplyStrategy.doAfterNResponses(
        ws.length,
        proxyClient => {
          ws.foreach(w => state.untrackEventRequest(client, w.bpRequestID))
          val synthesizedResponse = ReplyPacket.toWire(rawPacket.ID, reply.event_request.ClearAllBreakpoints())
          client.receiveFromJVM(synthesizedResponse)
        }
      )

      ws.foreach(w => {
        state.commandsAwaitingReply.addOne((w.freshPacketID, onReplyStrategy))
        state.outboundJvmBytes.add(w.bytes.clone())
      })

  def handleClearSingleEventRequest(client: ClientFacingProxyClient, state: IProxyState, rawPacket: raw.Command, cmd: command.event_request.Clear)(using idSizes: IdSizes): Unit =
    state.untrackEventRequest(client, EventRequestID(cmd.requestID))

    val onReplyStrategy = OnCommandReplyStrategy.justForward(PacketOrigin(rawPacket.ID, client))

    val newID = state.packetIdGenerator.next()

    rawPacket.withSwappedID(
      newID,
      packet => {
        state.commandsAwaitingReply.addOne((newID, onReplyStrategy))
        state.outboundJvmBytes.add(packet.raw.clone())
      }
    )

  def handleRegisterEventRequest(client: ClientFacingProxyClient, state: IProxyState, rawPacket: raw.Command, cmd: command.event_request.Set)(using idSizes: IdSizes): Unit =
    // todo: idSizes makes sense as a `using`, but state not so much
    implicit val _state_ : IProxyState = state
    val eventReceiptStrategy =
      if cmd.hasCount()
      then OnEventReceiptStrategy.forwardAndDiscard(client)
      else OnEventReceiptStrategy.justForward(client)

    val onReplyStrategy = OnCommandReplyStrategy
      .recordEventRequestAndForward(
        PacketOrigin(rawPacket.ID, client),
        eventReceiptStrategy,
        cmd
      )

    val newID = state.packetIdGenerator.next()

    rawPacket.withSwappedID(
      newID,
      packet => {
        state.commandsAwaitingReply.addOne((newID, onReplyStrategy))
        state.outboundJvmBytes.add(packet.raw.clone())
        //jvmSocketIO.write(packet.raw, 1000)
      }
    )

  def handleDisconnect(client: ClientFacingProxyClient, state: IProxyState, rawPacket: raw.Command, cmd: command.virtual_machine.Dispose)(using idSizes: IdSizes): Unit =
    // (a) All event requests are cancelled.
    // (b) All threads suspended by the thread-level suspend command or the VM-level suspend command are resumed as many times as necessary for them to run.
    // (c) Garbage collection is re-enabled in all cases where it was disabled

    // (a)
    println(s"disposing ${client.name}, eventRequests size is ${state.activeEventRequestsByClient.get(client.id).map(_.size).getOrElse(-1)}, allSize is ${state.allActiveEventRequests.size}")
    state.bpEventRequestsByClient.remove(client.id)
    state.activeEventRequestsByClient
      .remove(client.id)
      .foreach(
        eventRequestIdSetlike =>
          eventRequestIdSetlike
            .map(
              (eventRequestID, _) => state.allActiveEventRequests.remove(eventRequestID)
            )
            .foreach({
              case Some(TrackedEvent.ClientRequestedEvent(eventKind, _, eventRequestID, _)) =>
                val packetID = state.packetIdGenerator.next()
                val bytes = CommandPacket.toWire(packetID, command.event_request.Clear(eventKind, eventRequestID.asInt))
                state.commandsAwaitingReply.put(packetID, OnCommandReplyStrategy.doNothing)
                state.outboundJvmBytes.add(bytes)
                state.untrackEventRequest(client, eventRequestID)
              case _ => ()
            })
      )

    println(s"disposing ${client.name}, eventRequests size is ${state.activeEventRequestsByClient.get(client.id).map(_.size).getOrElse(-1)}, allSize is ${state.allActiveEventRequests.size}")

    // (b)
    state.suspendCountByClientByThread
      .get(client.id)
      .foreach(suspendCountByThread => {
        suspendCountByThread.foreach((threadID, count) => {
          (0 until count.get()).foreach(_ => {
            val packetID = state.packetIdGenerator.next()
            val bytes = CommandPacket.toWire(packetID, command.thread_reference.Resume(threadID))
            state.commandsAwaitingReply.put(packetID, OnCommandReplyStrategy.doNothing)
            state.outboundJvmBytes.add(bytes)
          })
        })
      })
    state.suspendCountByClientByThread.remove(client.id)

    // (c)
    state.disabledObjectCollectionsByClient.get(client.id) match
      case Some(objectIdSetlike) =>
        for ((objectID, _) <- objectIdSetlike) do
          val packetID = state.packetIdGenerator.next()
          val bytes = CommandPacket.toWire(packetID, command.object_reference.EnableCollection(objectID))
          state.commandsAwaitingReply.put(packetID, OnCommandReplyStrategy.doNothing)
          state.outboundJvmBytes.add(bytes)
        state.disabledObjectCollectionsByClient.remove(client.id)
      case None => ()

    println("dispose complete")
    client.receiveFromJVM(ReplyPacket.toWire(rawPacket.ID, reply.virtual_machine.Dispose()))
    state.clients.remove(client.id)

  def handleInboundClientPacket(client: ClientFacingProxyClient, rawPacket: raw.FinishedPacket)(using idSizes: IdSizes)(using state: IProxyState): Unit =
    rawPacket match
      case rawCmd: raw.Command =>
        Command
          .maybeGetParser(rawCmd)
          .map(_.bodyFromWire(rawCmd.body)) match
          case Some(cmd: command.event_request.ClearAllBreakpoints) =>
            handleClearAllBreakpoints(client, state, rawCmd, cmd)
          case Some(cmd: command.event_request.Clear) =>
            handleClearSingleEventRequest(client, state, rawCmd, cmd)
          case Some(cmd: command.event_request.Set) =>
            handleRegisterEventRequest(client, state, rawCmd, cmd)
          case Some(cmd: command.virtual_machine.Dispose) =>
            handleDisconnect(client, state, rawCmd, cmd)
          case Some(cmd: command.object_reference.DisableCollection) =>
            // actually should do our local update in response to reply for this
            state.disabledObjectCollectionsByClient.getOrElseUpdate(client.id, TrieMap()).put(cmd.objectID, ())
            justForwardSomeFinishedPacket(rawPacket, client)
          case Some(cmd: command.object_reference.EnableCollection) =>
            // should do our local update in response to reply for this?
            state.disabledObjectCollectionsByClient.getOrElseUpdate(client.id, TrieMap()).remove(cmd.objectID)
            justForwardSomeFinishedPacket(rawPacket, client)
          case Some(cmd: command.virtual_machine.Suspend) =>
            val suspendCountMap = state.suspendCountByClientByThread.getOrElse(client.id, TrieMap())
            // racy, should lock on `knownThreads`?
            state.knownThreads.foreach((threadID, _) => suspendCountMap.getOrElse(threadID, AtomicInteger(0)).incrementAndGet())
            justForwardSomeFinishedPacket(rawPacket, client)
          case Some(cmd: command.virtual_machine.Resume) =>
            val suspendCountMap = state.suspendCountByClientByThread.getOrElse(client.id, TrieMap())
            suspendCountMap.foreach((_, atomicInt) => atomicInt.decrementAndGet())
            justForwardSomeFinishedPacket(rawPacket, client)
          case Some(cmd: command.thread_reference.Suspend) =>
            state.suspendCountByClientByThread(client.id)(cmd.threadID).incrementAndGet()
            justForwardSomeFinishedPacket(rawPacket, client)
          case Some(cmd: command.thread_reference.Resume) =>
            state.suspendCountByClientByThread(client.id)(cmd.threadID).decrementAndGet()
            justForwardSomeFinishedPacket(rawPacket, client)
          case _ =>
            //
            // any other command we just blindly shuttle
            //
            justForwardSomeFinishedPacket(rawPacket, client)
      case rawReply: raw.Reply =>
        throw new RuntimeException(s"unexpected reply packet from client '${client.name}'")


  def justForwardSomeFinishedPacket(rawPacket: raw.FinishedPacket, client: ClientFacingProxyClient)(using state: IProxyState): Unit =
    val onReplyStrategy = OnCommandReplyStrategy.justForward(
      PacketOrigin(rawPacket.ID, client)
    )
    justForwardSomeFinishedPacket(rawPacket, onReplyStrategy)

  def justForwardSomeFinishedPacket(rawPacket: raw.FinishedPacket, onReplyStrategy: OnCommandReplyCallback)(using state: IProxyState): Unit =
    val newID = state.packetIdGenerator.next()
    rawPacket.withSwappedID(
      newID,
      packet => {
        state.commandsAwaitingReply.addOne((newID, onReplyStrategy))
        state.outboundJvmBytes.add(packet.raw.clone())
        //jvmSocketIO.write(packet.raw, 1000)
      }
    )
}