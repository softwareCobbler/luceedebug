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
import java.net.Inet4Address
import scala.annotation.tailrec

class LuceedebugJdwpProxy(
  actualJvmJdwpHost: String,
  actualJvmJdwpPort: Int,
  javaDebugHost: String,
  javaDebugPort: Int
) {
  println(s"[luceedebug-jdwp-proxy] starting proxy, with jvm jdwp target addr ${actualJvmJdwpHost}:${actualJvmJdwpPort}")

  private val internalLuceedebugJdwpSocket = {
    val addr = new InetSocketAddress("localhost", 0);
    val socket = new ServerSocket()
    socket.bind(addr)
    socket
  }
  private val frontendJavaSocket = {
    val addr = new InetSocketAddress(javaDebugHost, javaDebugPort);
    val socket = new ServerSocket()
    socket.bind(addr)
    socket
  }

  def getInternalLuceedebugHost() : String = internalLuceedebugJdwpSocket.getInetAddress().getHostAddress()
  def getInternalLuceedebugPort() : Int = internalLuceedebugJdwpSocket.getLocalPort()

  private class ProxyThreadHolder(val proxy: JdwpProxy, val thread: Thread)
  private val proxyThread = {
    val config = JdwpProxy.initProxyConfig(actualJvmJdwpHost, actualJvmJdwpPort)
    val proxy = JdwpProxy(config)
    val thread = new Thread(() => proxy.pumpPacketsUntilDisconnectBlocking(), "luceedebug-jdwp-proxy")
    thread.start()
    ProxyThreadHolder(proxy, thread)
  }

  val luceeThread = {
    val thread = new Thread(() => connectLucee(), "lucee-frontend-root")
    thread.start()
  }
  var java = {
    val thread = new Thread(() => connectJava(), "java-frontend-root")
    thread.start()
  }

  /**
   * luceedebug will remain connected the entire life of the vm
   */
  @tailrec
  private def connectLucee() : Unit =
      println(s"[luceedebug-jdwp-proxy] listening for internal luceedebug jdwp loopback on ${getInternalLuceedebugHost()}:${getInternalLuceedebugPort()}")
      val socket = internalLuceedebugJdwpSocket.accept()
      socket.setTcpNoDelay(true) // important for many tiny ~20 byte jdwp messages
      println(s"[luceedebug-jdwp-proxy] internal luceedebug jdwp loopback connected on ${getInternalLuceedebugHost()}:${getInternalLuceedebugPort()}")
      val proxy = proxyThread.proxy.createAndRegisterClient("lucee-frontend", socket)
      proxy.pumpPacketsUntilDisconnectBlocking()
      connectLucee()

  /**
   * this connection will come and go as the frontend connects/disconnects
   */
  @tailrec
  private def connectJava() : Unit =
    println(s"[luceedebug-jdwp-proxy] listening for inbound java frontend connection on ${javaDebugHost}:${javaDebugPort}")
    val socket = frontendJavaSocket.accept()
    socket.setTcpNoDelay(true) // important for many tiny ~20 byte jdwp messages
    println(s"[luceedebug-jdwp-proxy] java frontend connected on ${javaDebugHost}:${javaDebugPort}")
    val proxy = proxyThread.proxy.createAndRegisterClient("java-frontend", socket)
    proxy.pumpPacketsUntilDisconnectBlocking()
    connectJava()
}
