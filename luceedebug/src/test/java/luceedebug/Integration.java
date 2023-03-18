package luceedebug;

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

class Integration {
    @Test
    void hits_a_breakpoint_and_retrieves_variable_info() throws Throwable {
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
                dockerTestDir.resolve("app1").toFile(),
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
            
            final var socket = new Socket();
            socket.connect(new InetSocketAddress("localhost", 10000));
            final var launcher = DSPLauncher.createClientLauncher(dapClient, socket.getInputStream(), socket.getOutputStream());
            launcher.startListening();
            final var dapServer = launcher.getRemoteProxy();

            DapUtils.init(dapServer).join();
            DapUtils.attach(dapServer).join();

            DapUtils
                .setBreakpoints(dapServer, "/var/www/a.cfm", 3)
                .join();

            final var threadID_future = new CompletableFuture<Integer>();
            dapClient.stopped_handler = stoppedEventArgs -> {
                threadID_future.complete(stoppedEventArgs.getThreadId());
            };
            
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

            requestThreadToBeBlockedByBreakpoint.start();

            final var threadID = threadID_future.get(2500, TimeUnit.MILLISECONDS);

            final var stackTrace = DapUtils
                .getStackTrace(dapServer, threadID)
                .join();

            assertEquals(stackTrace.getTotalFrames(), 2);

            final var scopes = DapUtils
                .getScopes(
                    dapServer,
                    stackTrace.getStackFrames()[0].getId()
                )
                .join()
                .getScopes();

            final var argScope = ((Supplier<Scope>)() -> {
                for (var scope : scopes) {
                    if (scope.getName().equals("arguments")) {
                        return scope;
                    }
                }
                return null;
            }).get();

            assertNotNull(argScope, "got arg scope");

            final var variables = DapUtils
                .getVariables(dapServer, argScope)
                .join()
                .getVariables();

            final var target = ((Supplier<Variable>)() -> {
                for (var variable : variables) {
                    if (variable.getName().equals("n")) {
                        return variable;
                    }
                }
                return null;
            }).get();

            assertNotNull(target, "got expected variable");
            assertEquals(target.getValue(), "42.0");

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
