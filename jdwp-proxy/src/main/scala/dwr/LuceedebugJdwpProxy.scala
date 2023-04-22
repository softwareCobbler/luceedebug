package dwr

import dwr.jdwp_proxy._
import scala.concurrent.Future
import java.net.ServerSocket
import java.net.InetSocketAddress
import scala.concurrent.ExecutionContext
import java.net.Socket
import java.util.concurrent.Executors
import scala.util.Success
import scala.util.Failure

@main
def ok() : Unit =
  val ARRAY : Byte = '['
  println(ARRAY)

class LuceedebugJdwpProxy(
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
