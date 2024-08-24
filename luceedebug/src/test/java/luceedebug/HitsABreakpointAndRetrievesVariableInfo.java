package luceedebug;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.eclipse.lsp4j.debug.Scope;
import org.eclipse.lsp4j.debug.Variable;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.github.dockerjava.api.DockerClient;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.javanet.NetHttpTransport;

import luceedebug.testutils.DapUtils;
import luceedebug.testutils.DockerUtils;
import luceedebug.testutils.DockerUtils.HostPortBindings;
import luceedebug.testutils.LuceeUtils;
import luceedebug.testutils.TestParams.DockerInfo;

class HitsABreakpointAndRetrievesVariableInfo {
    @ParameterizedTest
    @MethodSource("luceedebug.testutils.TestParams#getDockerFilePaths")
    void a(DockerInfo dockerInfo) throws Throwable {
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
                .setBreakpoints(dapServer, "/var/www/a.cfm", 3)
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
                () -> {
                    requestThreadToBeBlockedByBreakpoint.start();
                }
            )
                .get(1000, TimeUnit.MILLISECONDS)
                .getThreadId();

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
