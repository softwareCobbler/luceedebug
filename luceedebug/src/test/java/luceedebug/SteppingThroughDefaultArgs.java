package luceedebug;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.github.dockerjava.api.DockerClient;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.javanet.NetHttpTransport;

import luceedebug.testutils.DapUtils;
import luceedebug.testutils.DockerUtils;
import luceedebug.testutils.LuceeUtils;
import luceedebug.testutils.DockerUtils.HostPortBindings;

import org.eclipse.lsp4j.debug.launch.DSPLauncher;

class SteppingThroughDefaultArgs {
    @Test
    void a() throws Throwable {
        final Path projectRoot = Paths.get("").toAbsolutePath();
        final Path dockerTestDir = projectRoot.resolve("../test/docker").normalize();

        final DockerClient dockerClient = DockerUtils.getDefaultDockerClient();

        final String imageID = DockerUtils
            .buildOrGetImage(dockerClient, dockerTestDir.resolve("Dockerfile").toFile())
            .getImageID();

        final String containerID = DockerUtils
            .getFreshDefaultContainer(
                dockerClient,
                imageID,
                projectRoot.toFile(),
                dockerTestDir.resolve("stepping_through_default_args").toFile(),
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
            
            final var FIXME_socket_needs_close = new Socket();
            FIXME_socket_needs_close.connect(new InetSocketAddress("localhost", portBindings.dap));
            final var launcher = DSPLauncher.createClientLauncher(dapClient, FIXME_socket_needs_close.getInputStream(), FIXME_socket_needs_close.getOutputStream());
            launcher.startListening();
            final var dapServer = launcher.getRemoteProxy();

            DapUtils.init(dapServer).join();
            DapUtils.attach(dapServer, Map.of("stepIntoUdfDefaultValueInitFrames", (Object)Boolean.TRUE)).join();

            DapUtils
                .setBreakpoints(dapServer, "/var/www/a.cfm", 10)
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

            //
            // we use stepIn/stepOver arbitrarly, for our purposes in this
            //

            final var threadID = DapUtils.doWithStoppedEventFuture(
                dapClient,
                () -> requestThreadToBeBlockedByBreakpoint.start()
            ).get(1000, TimeUnit.MILLISECONDS).getThreadId();

            {
                DapUtils.doWithStoppedEventFuture(
                    dapClient,
                    () -> DapUtils.stepIn(dapServer, threadID)
                ).get(1000, TimeUnit.MILLISECONDS);

                // a
                assertEquals(
                    3,
                    DapUtils
                        .getStackTrace(dapServer, threadID)
                        .get(1, TimeUnit.SECONDS)
                        .getStackFrames()[0]
                        .getLine()
                );
            }

            {
                DapUtils.doWithStoppedEventFuture(
                    dapClient,
                    () -> DapUtils.stepIn(dapServer, threadID)
                ).get(1000, TimeUnit.MILLISECONDS);

                // c (b runs, but we can't seem to stop on it)
                assertEquals(
                    4,
                    DapUtils
                        .getStackTrace(dapServer, threadID)
                        .get(1, TimeUnit.SECONDS)
                        .getStackFrames()[0]
                        .getLine()
                );
            }

            {
                DapUtils.doWithStoppedEventFuture(
                    dapClient,
                    () -> DapUtils.stepIn(dapServer, threadID)
                ).get(1000, TimeUnit.MILLISECONDS);

                // e
                assertEquals(
                    5,
                    DapUtils
                        .getStackTrace(dapServer, threadID)
                        .get(1, TimeUnit.SECONDS)
                        .getStackFrames()[0]
                        .getLine()
                );
            }

            {
                DapUtils.doWithStoppedEventFuture(
                    dapClient,
                    () -> DapUtils.stepOver(dapServer, threadID)
                ).get(1000, TimeUnit.MILLISECONDS);

                // empty line (would like to not hit this if possible)
                assertEquals(
                    6,
                    DapUtils
                        .getStackTrace(dapServer, threadID)
                        .get(1, TimeUnit.SECONDS)
                        .getStackFrames()[0]
                        .getLine()
                );
            }

            {
                DapUtils.doWithStoppedEventFuture(
                    dapClient,
                    () -> DapUtils.stepOver(dapServer, threadID)
                ).get(1000, TimeUnit.MILLISECONDS);

                // function name declaration
                assertEquals(
                    2,
                    DapUtils
                        .getStackTrace(dapServer, threadID)
                        .get(1, TimeUnit.SECONDS)
                        .getStackFrames()[0]
                        .getLine()
                );

                DapUtils.doWithStoppedEventFuture(
                    dapClient,
                    () -> DapUtils.stepOver(dapServer, threadID)
                ).get(1000, TimeUnit.MILLISECONDS);

                // return statement
                assertEquals(
                    7,
                    DapUtils
                        .getStackTrace(dapServer, threadID)
                        .get(1, TimeUnit.SECONDS)
                        .getStackFrames()[0]
                        .getLine()
                );
            }

            {
                DapUtils.doWithStoppedEventFuture(
                    dapClient,
                    () -> DapUtils.stepOver(dapServer, threadID)
                ).get(1000, TimeUnit.MILLISECONDS);

                // back to callsite
                assertEquals(
                    10,
                    DapUtils
                        .getStackTrace(dapServer, threadID)
                        .get(1, TimeUnit.SECONDS)
                        .getStackFrames()[0]
                        .getLine()
                );
            }

            DapUtils.disconnect(dapServer).join();
        }
        finally {
            dockerClient.stopContainerCmd(containerID).exec();
            dockerClient.removeContainerCmd(containerID).exec();
        }
    }
}
