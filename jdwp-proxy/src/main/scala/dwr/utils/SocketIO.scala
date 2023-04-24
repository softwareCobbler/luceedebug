package dwr.utils

import dwr.utils.DummySocketIO.nilArray

import scala.concurrent.duration.Duration
import scala.concurrent.duration.MILLISECONDS
import scala.concurrent.Await
import java.io.InputStream
import java.io.OutputStream
import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors
import scala.concurrent.Future
import java.io.IOException
import java.net.SocketException

abstract class ISocketIO {
  def chunkedReader(chunkSize: Int) : () => Result[Array[Byte]]
  def write(bytes: Array[Byte], timeout_ms: Int) : Unit
  def read(n: Int, timeout_ms: Int) : Array[Byte]
}

class DummySocketIO extends ISocketIO {
  def chunkedReader(chunkSize: Int): () => Result[Array[Byte]] = () => Result.Closed(None)
  def write(bytes: Array[Byte], timeout_ms: Int): Unit = ()
  def read(n: Int, timeout_ms: Int): Array[Byte] = nilArray
}

object DummySocketIO {
  private val nilArray = new Array[Byte](0)
}

enum Result[T]:
  case OK(v: T)
  case Closed(err: Option[IOException | SocketException])
  def getOrFail() : T =
    this match
      case OK(bytes) => bytes
      case Closed(Some(e)) =>
        e.printStackTrace()
        System.exit(1).asInstanceOf[T]
      case Closed(_) =>
        println("Result.getOrFail() - fatal, no captured exception")
        System.exit(1).asInstanceOf[T]

class SocketIO(private val inStream: InputStream, private val outStream: OutputStream) extends ISocketIO {
  private implicit val executionContext : ExecutionContext = ExecutionContext.fromExecutor(
    Executors.newFixedThreadPool(3)
  )

  def chunkedReader(chunkSize: Int) : () => Result[Array[Byte]] = {
    val buffer = new Array[Byte](chunkSize);
    () => {
      try {
        val readSize = inStream.read(buffer)
        if readSize == -1
        then Result.Closed(err = None)
        else Result.OK(buffer.take(readSize))
      }
      catch {
        case e: IOException => Result.Closed(err = Some(e))
        // are we not closing when we should?
        case e: SocketException => Result.Closed(err = Some(e))
        case e => throw e
      }
    }
  }

  def write(bytes: Array[Byte], timeout_ms: Int) : Unit =
    outStream.write(bytes)
    outStream.flush()
    // val duration = Duration(timeout_ms, MILLISECONDS)
    // Await.result(
    //   Future {
    //     outStream.write(bytes)
    //     outStream.flush()
    //   },
    //   duration
    // )

  def read(n: Int, timeout_ms: Int) : Array[Byte] =
    val duration = Duration(timeout_ms, MILLISECONDS)
    val bytes = Await.result(Future{inStream.readNBytes(n)}, duration)
    if bytes.length != n
      then throw new IOException(s"Expected ${n} bytes but read ${bytes.length}")
      else bytes
}
