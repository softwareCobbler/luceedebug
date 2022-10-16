package luceedebug.test;

import java.net.ServerSocket;

public class BlockUntilSocketConnection {
    public static void doit(int port) throws Throwable {
        ServerSocket socket = new ServerSocket(port);
        socket.accept().close();
        socket.close();
    }    
}
