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
  LuceedebugJdwpProxy(
    jdwpHost = "localhost",
    jdwpPort = 9999,
    luceeClientHost = "localhost",
    luceeClientPort = 10000,
    jvmClientHost = "localhost",
    jvmClientPort = 10001,
  )

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
        (),
        "luceedebug-jdwp-proxy"
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
  
  val luceeContext : ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadScheduledExecutor(t => Thread(t, "ld-lucee-frontend")))
  val javaContext : ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadScheduledExecutor(t => Thread(t, "ld-java-frontend")))
  
  var lucee = connectLucee()
  var java = connectJava()
  
  private def connectLucee() : ConnectionState =
    Listening({
      implicit val context = luceeContext
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
      implicit val context = javaContext
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
