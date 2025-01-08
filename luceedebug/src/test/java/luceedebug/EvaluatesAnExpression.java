package luceedebug;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import com.github.dockerjava.api.DockerClient;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.javanet.NetHttpTransport;

import luceedebug.testutils.DapUtils;
import luceedebug.testutils.DockerUtils;
import luceedebug.testutils.LuceeUtils;
import luceedebug.testutils.TestParams.LuceeAndDockerInfo;
import luceedebug.testutils.DockerUtils.HostPortBindings;

import org.eclipse.lsp4j.debug.launch.DSPLauncher;

class EvaluatesAnExpression {
    @ParameterizedTest
    @MethodSource("luceedebug.testutils.TestParams#getLuceeAndDockerInfo")
    void a(LuceeAndDockerInfo dockerInfo) throws Throwable {
        final DockerClient dockerClient = DockerUtils.getDefaultDockerClient();

        final String imageID = DockerUtils
            .buildOrGetImage(dockerClient, dockerInfo.dockerFile)
            .getImageID();

        final String containerID = DockerUtils
            .getFreshDefaultContainer(
                dockerClient,
                imageID,
                dockerInfo.luceedebugProjectRoot.toFile(),
                dockerInfo.getTestWebRoot("app1"),
                new int[][]{
                    new int[]{8888,8888},
                    new int[]{10000,10000}
                }
            )
            .getContainerID();

        dockerClient
            .startContainerCmd(containerID)
            .exec();

        HostPortBindings portBindings = DockerUtils.getPublishedHostPortBindings(dockerClient, containerID);

        try {
            LuceeUtils.pollForServerIsActive("http://localhost:" + portBindings.http + "/heartbeat.cfm");

            final var dapClient = new DapUtils.MockClient();
            
            final var socket = new Socket();
            socket.connect(new InetSocketAddress("localhost", portBindings.dap));
            final var launcher = DSPLauncher.createClientLauncher(dapClient, socket.getInputStream(), socket.getOutputStream());
            launcher.startListening();
            final var dapServer = launcher.getRemoteProxy();

            DapUtils.init(dapServer).join();
            DapUtils.attach(dapServer).join();

            DapUtils
                .setBreakpoints(dapServer, "/var/www/a.cfm", 4)
                .join();

            final var requestThreadToBeBlockedByBreakpoint = new java.lang.Thread(() -> {
                final var requestFactory = new NetHttpTransport().createRequestFactory();
                HttpRequest request;
                try {
                    request = requestFactory.buildGetRequest(new GenericUrl("http://localhost:" + portBindings.http + "/a.cfm"));
                    request.execute().disconnect();
                }
                catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            final var threadID = DapUtils.doWithStoppedEventFuture(
                dapClient,
                () -> requestThreadToBeBlockedByBreakpoint.start()
            ).get(1000, TimeUnit.MILLISECONDS).getThreadId();

            final var frameID = DapUtils
                .getStackTrace(dapServer, threadID)
                .join()
                .getStackFrames()[0]
                .getId();

            assertEquals(
                "false",
                DapUtils.evaluate(dapServer, frameID, "isNull(arguments.n)").join().getResult(),
                "evaluation result as expected"
            );

            assertEquals(
                "\"1,2,3\"",
                DapUtils.evaluate(dapServer, frameID, "arrayToList([1,2,3], \",\")").join().getResult(),
                "evaluation result as expected"
            );
            
            assertEquals(
                "\"bar\"",
                DapUtils.evaluate(dapServer, frameID, "e").join().getResult(),
                "e is initialized to \"bar\""
            );

            // An expression that will throw an exception, should not clobber "e" in the local scope
            DapUtils.evaluate(dapServer, frameID, "zzz");

            assertEquals(
                "\"bar\"",
                DapUtils.evaluate(dapServer, frameID, "e").join().getResult(),
                "e is still \"bar\""
            );

            DapUtils.continue_(dapServer, threadID);
            
            requestThreadToBeBlockedByBreakpoint.join();

            DapUtils.disconnect(dapServer);

            //socket.close(); // how to let launcher know we want to do this?
        }
        finally {
            dockerClient.stopContainerCmd(containerID).exec();
            dockerClient.removeContainerCmd(containerID).exec();
        }
    }
}
