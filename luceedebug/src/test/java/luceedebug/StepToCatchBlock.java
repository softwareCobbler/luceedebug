package luceedebug;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.github.dockerjava.api.DockerClient;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.javanet.NetHttpTransport;

import luceedebug.testutils.DapUtils;
import luceedebug.testutils.DockerUtils;
import luceedebug.testutils.LuceeUtils;

import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;

class StepToCatchBlock {
    @Test
    void stepping_through_default_args() throws Throwable {
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
                dockerTestDir.resolve("step_to_catch_block").toFile(),
                new int[][]{
                    new int[]{8888,8888},
                    new int[]{10000,10000}
                }
            )
            .getContainerID();

        dockerClient
            .startContainerCmd(containerID)
            .exec();

        try {
            LuceeUtils.pollForServerIsActive("http://localhost:8888/heartbeat.cfm");

            final var dapClient = new DapUtils.MockClient();

            final var FIXME_socket_needs_close = new Socket();
            FIXME_socket_needs_close.connect(new InetSocketAddress("localhost", 10000));
            final var launcher = DSPLauncher.createClientLauncher(dapClient, FIXME_socket_needs_close.getInputStream(), FIXME_socket_needs_close.getOutputStream());
            launcher.startListening();
            final var dapServer = launcher.getRemoteProxy();

            DapUtils.init(dapServer).join();
            DapUtils.attach(dapServer).join();

            DapUtils
                .setBreakpoints(dapServer, "/var/www/a.cfm", 29)
                .join();

            final var requestThreadToBeBlockedByBreakpoint = new java.lang.Thread(() -> {
                final var requestFactory = new NetHttpTransport().createRequestFactory();
                HttpRequest request;
                try {
                    request = requestFactory.buildGetRequest(new GenericUrl("http://localhost:8888/a.cfm"));
                    request.execute().disconnect();
                }
                catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            final var threadID = doWithStoppedEventFuture(
                dapClient,
                () -> requestThreadToBeBlockedByBreakpoint.start()
            ).get(1000, TimeUnit.MILLISECONDS).getThreadId();

            {
                doWithStoppedEventFuture(
                    dapClient,
                    () -> DapUtils.stepIn(dapServer, threadID)
                ).get(1000, TimeUnit.MILLISECONDS);

                // finally { <<<
                //   0+0;
                // }
                assertEquals(
                    19,
                    DapUtils
                        .getStackTrace(dapServer, threadID)
                        .get(1, TimeUnit.SECONDS)
                        .getStackFrames()[0]
                        .getLine()
                );
            }

            {
                doWithStoppedEventFuture(
                    dapClient,
                    () -> DapUtils.stepIn(dapServer, threadID)
                ).get(1000, TimeUnit.MILLISECONDS);

                // finally {
                //   0+0; <<<
                // }
                assertEquals(
                    20,
                    DapUtils
                        .getStackTrace(dapServer, threadID)
                        .get(1, TimeUnit.SECONDS)
                        .getStackFrames()[0]
                        .getLine()
                );
            }

            {
                doWithStoppedEventFuture(
                    dapClient,
                    () -> DapUtils.stepIn(dapServer, threadID)
                ).get(1000, TimeUnit.MILLISECONDS);

                // catch (any e) { <<<
                //     0+0;
                // }
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
                doWithStoppedEventFuture(
                    dapClient,
                    () -> DapUtils.stepIn(dapServer, threadID)
                ).get(1000, TimeUnit.MILLISECONDS);

                // catch (any e) {
                //     0+0; <<<
                // }
                assertEquals(
                    7,
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

    /**
     * This does not work recursively, and requires that exactly and only a single stop event (i.e. the target stop event)
     * be fired during the wait on the returned future.
     *
     * We might want to await this here, rather than allow the caller to do so, where if they forget to wait it's likely a bug.
     *
     * "do some work that should trigger the debugee to soon (microseconds) emit a stop event and return a future that resolves on receipt of that stopped event"
     */
    private static CompletableFuture<StoppedEventArguments> doWithStoppedEventFuture(DapUtils.MockClient client, Runnable f) {
        final var future = new CompletableFuture<StoppedEventArguments>();
        client.stopped_handler = stoppedEventArgs -> {
            client.stopped_handler = null; // concurrency issues? Callers should be synchronous with respect to this action though.
            future.complete(stoppedEventArgs);
        };
        f.run();
        return future;
    };
}
