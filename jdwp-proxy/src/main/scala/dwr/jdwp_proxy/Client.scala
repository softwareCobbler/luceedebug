package dwr.jdwp_proxy

import dwr.jdwp.packet.raw
import dwr.utils.{ISocketIO, Result}

import java.util.concurrent.atomic.AtomicInteger
import scala.util.control.Breaks.{break, breakable}

trait IClient {
  def pumpPacketsUntilDisconnectBlocking(): Unit;
}

final class ClientID(clientID: Int) extends AnyVal {
  def asInt : Int = clientID
}

final class ClientIdGenerator {
  private val nextID = AtomicInteger()
  def next() : ClientID = ClientID(nextID.getAndIncrement())
}

class ClientFacingProxyClient(
              val id: ClientID,
              val io: ISocketIO,
              val receiveFromClient: (/*todo: don't we have a ref to this?...*/ client: ClientFacingProxyClient, bytes: Array[Byte]) => Unit,
              val receiveFromJVM: Array[Byte] => Unit,
              val name: String
            ) extends IClient {
  private def worker() =
    val reader = io.chunkedReader(1024 * 1024)
    breakable {
      while true do
        reader() match
          case Result.OK(bytes) =>
            receiveFromClient(this, bytes)
          case Result.Closed(_) =>
            println(s"client ${name} died")
            break
    }

  override def pumpPacketsUntilDisconnectBlocking(): Unit = worker()
}

class JvmFacingProxyClient(
                            val id: ClientID,
                            val io: ISocketIO,
                            val receiveFromJVM: Array[Byte] => Unit,
                            val receiveFromClient: (ClientFacingProxyClient, raw.FinishedPacket) => Unit,
                          ) extends IClient {

  private def worker(): Unit =
    val reader = io.chunkedReader(1024 * 1024)
    while true do
      reader() match
        case Result.OK(bytes) => receiveFromJVM(bytes)
        case Result.Closed(Some(err)) =>
          err.printStackTrace()
          System.exit(1)
        case Result.Closed(_) =>
          println("JVM connection unexpectedly closed.")
          System.exit(1)

  override def pumpPacketsUntilDisconnectBlocking(): Unit = worker()
}
