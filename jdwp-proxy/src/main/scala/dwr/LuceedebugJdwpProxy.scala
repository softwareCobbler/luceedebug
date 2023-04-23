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
    actualJvmJdwpHost = "localhost",
    actualJvmJdwpPort = 9999,
    luceeDebugJdwpLoopbackHost = "localhost",
    luceeDebugJdwpLoopbackPort = 10001,
    javaDebugHost = "localhost",
    javaDebugPort = 10002,
  )

class LuceedebugJdwpProxy(
  actualJvmJdwpHost: String,
  actualJvmJdwpPort: Int,
  luceeDebugJdwpLoopbackHost: String,
  luceeDebugJdwpLoopbackPort: Int,
  javaDebugHost: String,
  javaDebugPort: Int
) {
  private class ProxyThread(val proxy: JdwpProxy, val thread: Thread)
  private val proxyThread = {
    var proxy : Option[JdwpProxy] = None
    val lock = Object()
    lock.synchronized {
      val thread = new Thread(() => 
        new JdwpProxy(
          actualJvmJdwpHost,
          actualJvmJdwpPort, 
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
  
  val luceeContext : ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadScheduledExecutor(t => Thread(t, "ld-lucee-loopback")))
  val javaContext : ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadScheduledExecutor(t => Thread(t, "ld-java-frontend")))
  
  var lucee = connectLucee()
  var java = connectJava()

  /**
    * luceedebug will remain connected the entire life of the vm
    *
    * @return
    */
  private def connectLucee() : ConnectionState =
    Listening({
      implicit val context = luceeContext
      val future = Future {
        println(s"[luceedebug-jdwp-proxy] listening jdwp connection A on ${luceeDebugJdwpLoopbackHost}:${luceeDebugJdwpLoopbackPort}")
        val socket = listenOn(luceeDebugJdwpLoopbackHost, luceeDebugJdwpLoopbackPort)
        println("[luceedebug-jdwp-proxy] got proxy conn A")
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

  /**
    * this connection will come and go as the frontend connects/disconnects
    */
  private def connectJava() : ConnectionState =
    Listening({
      implicit val context = javaContext
      val future = Future {
        println(s"[luceedebug-jdwp-proxy] listening jdwp connection B on ${javaDebugHost}:${javaDebugHost}")
        val socket = listenOn(javaDebugHost, javaDebugPort)
        println("[luceedebug-jdwp-proxy] got proxy conn B")
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
