package luceedebug.testutils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.SocketException;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.javanet.NetHttpTransport;

public class LuceeUtils {
    public static void pollForServerIsActive(String url) throws IOException, InterruptedException {
        HttpRequestFactory requestFactory = new NetHttpTransport().createRequestFactory();
        boolean serverUp = false;
        for (int i = 0; i < 100; i++) {
            HttpRequest request = requestFactory.buildGetRequest(new GenericUrl(url));
            try {
                HttpResponse response = request.execute();
                try {
                    assertEquals("OK", response.parseAsString());
                    serverUp = true;
                }
                finally {
                    response.disconnect();
                }
            }
            catch (SocketException s) {
                // discard, server's not serving yet
                Thread.sleep(25);
            }
        }
        assertTrue(serverUp, "server is up");
    }
}
