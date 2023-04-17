package luceedebug;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
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

class SteppingWorksAsExpectedOnSinglelineStatementWithManySubexpressions {
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
                dockerTestDir.resolve("stepping_works_as_expected_on_singleline_statement_with_many_subexpressions").toFile(),
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
            DapUtils.attach(dapServer).join();

            DapUtils
                .setBreakpoints(dapServer, "/var/www/a.cfm", 6)
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

            final var threadID = DapUtils.doWithStoppedEventFuture(dapClient, () -> {
                requestThreadToBeBlockedByBreakpoint.start();
            })
            .get(1000, TimeUnit.MILLISECONDS)
            .getThreadId();

            //
            // ^N is "^" points at expected column (though we don't get column info) and "N" is frame number
            // ^1 is at top of stack, ^2 is the frame below it, ...
            //

            {
                // foo(n) { ... }
                //     
                // foo(1).foo(2).foo(3).foo(4);
                //     ^1
                final var frames = DapUtils
                    .getStackTrace(dapServer, threadID)
                    .join()
                    .getStackFrames();
                assertEquals(1, frames.length);
                assertEquals("??", frames[0].getName());
                assertEquals(6, frames[0].getLine());
            }

            DapUtils.doWithStoppedEventFuture(
                dapClient,
                () -> DapUtils.stepIn(dapServer, threadID).join()
            ).get(1000, TimeUnit.MILLISECONDS);

            {
                // foo(n) { ... }
                //     ^1
                // foo(1).foo(2).foo(3).foo(4);
                //     ^2
                final var frames = DapUtils
                    .getStackTrace(dapServer, threadID)
                    .join()
                    .getStackFrames();
                assertEquals(2, frames.length);
                assertEquals("FOO", frames[0].getName());
                assertEquals(2, frames[0].getLine());
            }

            DapUtils.doWithStoppedEventFuture(
                dapClient,
                () -> DapUtils.stepOut(dapServer, threadID).join()
            ).get(1000, TimeUnit.MILLISECONDS);

            {
                // foo(n) { ... }
                //
                // foo(1).foo(2).foo(3).foo(4);
                //     ^1 (out-but-not-yet-stepped)
                final var frames = DapUtils
                    .getStackTrace(dapServer, threadID)
                    .join()
                    .getStackFrames();
                assertEquals(1, frames.length);
                assertEquals("??", frames[0].getName());
                assertEquals(6, frames[0].getLine());
            }

            DapUtils.doWithStoppedEventFuture(
                dapClient,
                () -> DapUtils.stepIn(dapServer, threadID).join()
            ).get(1000, TimeUnit.MILLISECONDS);

            {
                // foo(n) { ... }
                //     ^1
                // foo(1).foo(2).foo(3).foo(4);
                //            ^2
                final var frames = DapUtils
                    .getStackTrace(dapServer, threadID)
                    .join()
                    .getStackFrames();
                assertEquals(2, frames.length);
                assertEquals("foo", frames[0].getName(), "'foo' instead of 'FOO', different case the second time around");
                assertEquals(2, frames[0].getLine());
            }

            DapUtils.doWithStoppedEventFuture(
                dapClient,
                () -> DapUtils.stepOut(dapServer, threadID).join()
            ).get(1000, TimeUnit.MILLISECONDS);

            {
                // foo(n) { ... }
                //
                // foo(1).foo(2).foo(3).foo(4);
                //            ^ (out-but-not-yet-stepped)
                final var frames = DapUtils
                    .getStackTrace(dapServer, threadID)
                    .join()
                    .getStackFrames();
                assertEquals(1, frames.length);
                assertEquals("??", frames[0].getName());
                assertEquals(6, frames[0].getLine());
            }

            DapUtils.doWithStoppedEventFuture(
                dapClient,
                () -> DapUtils.stepOver(dapServer, threadID).join()
            ).get(1000, TimeUnit.MILLISECONDS);

            {
                // foo(n) { ... }
                //
                // foo(1).foo(2).foo(3).foo(4);
                //               ^1
                final var frames = DapUtils
                    .getStackTrace(dapServer, threadID)
                    .join()
                    .getStackFrames();
                assertEquals(1, frames.length);
                assertEquals("??", frames[0].getName());
                assertEquals(6, frames[0].getLine());
            }

            DapUtils.doWithStoppedEventFuture(
                dapClient,
                () -> DapUtils.stepOver(dapServer, threadID).join()
            ).get(1000, TimeUnit.MILLISECONDS);

            {
                // foo(n) { ... }
                //
                // foo(1).foo(2).foo(3).foo(4);
                //                      ^1
                final var frames = DapUtils
                    .getStackTrace(dapServer, threadID)
                    .join()
                    .getStackFrames();
                assertEquals(1, frames.length);
                assertEquals("??", frames[0].getName());
                assertEquals(6, frames[0].getLine());
            }

            DapUtils.doWithStoppedEventFuture(
                dapClient,
                () -> DapUtils.stepOver(dapServer, threadID).join()
            ).get(1000, TimeUnit.MILLISECONDS);

            {
                // foo(n) { ... }
                //
                // foo(1).foo(2).foo(3).foo(4);
                // <next line>
                // ^1
                final var frames = DapUtils
                    .getStackTrace(dapServer, threadID)
                    .join()
                    .getStackFrames();
                assertEquals(1, frames.length);
                assertEquals("??", frames[0].getName());
                assertEquals(7, frames[0].getLine());
            }

            DapUtils
                .disconnect(dapServer)
                .join();

            // DapUtils.getStackTrace(dapServer, threadID).join().getStackFrames()[0];
        }
        finally {
            dockerClient.stopContainerCmd(containerID).exec();
            dockerClient.removeContainerCmd(containerID).exec();
        }
    }
}
