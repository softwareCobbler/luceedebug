package dwr.utils

import scala.concurrent.duration.Duration
import scala.concurrent.duration.{MILLISECONDS}
import scala.concurrent.Await
import java.io.InputStream
import java.io.OutputStream
import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors
import scala.concurrent.Future
import java.io.IOException

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
