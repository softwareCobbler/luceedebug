package luceedebug;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports.Binding;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;

import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;

class Integration {
    @Test
    void hits_a_breakpoint_and_retrieves_variable_info() throws Throwable {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var httpClient = new ApacheDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost())
            .sslConfig(config.getSSLConfig())
            .maxConnections(100)
            .connectionTimeout(Duration.ofSeconds(30))
            .responseTimeout(Duration.ofSeconds(45))
            .build();
        DockerClient dockerClient = DockerClientImpl.getInstance(config, httpClient);

        final Path projectRoot = Paths.get("").toAbsolutePath();
        final Path dockerTestDir = Paths.get(projectRoot.toString(), "../test/docker").normalize();

        var imageID = "luceedebug";

        var hostConfig = new HostConfig();
        hostConfig.setBinds(
            new Bind(
                Paths.get(projectRoot.toString(), "build/libs/").toString(),
                new Volume("/build/")
            ),
            new Bind(
                Paths.get(dockerTestDir.toString(), "app1").toString(),
                new Volume("/var/www/")
            )
        );
        hostConfig.withPortBindings(
            new PortBinding(new Binding(null, "8888"), new ExposedPort(8888)), // http
            new PortBinding(new Binding(null, "10000"), new ExposedPort(10000)) // luceedebug
        );

        CreateContainerResponse container = dockerClient
            .createContainerCmd(imageID)
            .withHostConfig(hostConfig)
            .withExposedPorts(
                new ExposedPort(8888),
                new ExposedPort(10000)
            )
            .exec();

        System.out.println("Fresh container ID: " + container.getId());

        try {
            dockerClient.startContainerCmd(container.getId()).exec();
        }
        catch (Throwable e) {
            e.printStackTrace();
            throw e;
        }

        try {
            HttpRequestFactory requestFactory = new NetHttpTransport().createRequestFactory();
            boolean serverUp = false;
            for (int i = 0; i < 100; i++) {
                HttpRequest request = requestFactory.buildGetRequest(new GenericUrl("http://localhost:8888/heartbeat.cfm"));
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
                    java.lang.Thread.sleep(25);
                }
            }

            assertTrue(serverUp, "server is up");

            var dapClient = new MockClient();
            var socket = new Socket();
            socket.connect(new InetSocketAddress("localhost", 10000));
            var launcher = DSPLauncher.createClientLauncher(dapClient, socket.getInputStream(), socket.getOutputStream());
            launcher.startListening();
            var initArgs = new InitializeRequestArguments();
            initArgs.setClientID("test");
            launcher.getRemoteProxy().initialize(initArgs).join();
            launcher.getRemoteProxy().attach(new HashMap<String,Object>()).join();

            var breakpoints = new SetBreakpointsArguments();
            var bp = new SourceBreakpoint();
            var source = new Source();
            source.setPath("/var/www/a.cfm");
            bp.setLine(3);
            breakpoints.setBreakpoints(
                new SourceBreakpoint[]{bp}
            );
            breakpoints.setSource(source);
            try {
                launcher.getRemoteProxy().setBreakpoints(breakpoints).join();
            }
            catch (Throwable e) {
                e.printStackTrace();
                throw e;
            }

            var v = new CompletableFuture<Integer>();
            dapClient.stopped_handler = stoppedEventArgs -> {
                v.complete(stoppedEventArgs.getThreadId());
            };
            
            
            var requestThread = new java.lang.Thread(() -> {
                HttpRequest request;
                try {
                    request = requestFactory.buildGetRequest(new GenericUrl("http://localhost:8888/a.cfm"));
                    request.execute().disconnect();
                }
                catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            requestThread.start();

            var threadID = v.get(2500, TimeUnit.MILLISECONDS);

            var stackTraceArgs = new StackTraceArguments();
            stackTraceArgs.setThreadId(threadID);
            var stackTrace = launcher.getRemoteProxy().stackTrace(stackTraceArgs).join();
            assertEquals(stackTrace.getTotalFrames(), 2);
            var scopesArgs = new ScopesArguments();
            scopesArgs.setFrameId(stackTrace.getStackFrames()[0].getId());
            var scopes = launcher.getRemoteProxy().scopes(scopesArgs).join().getScopes();
            var argScope = ((Supplier<Scope>)() -> {
                for (var scope : scopes) {
                    if (scope.getName().equals("arguments")) {
                        return scope;
                    }
                }
                return null;
            }).get();
            assertNotNull(argScope, "got arg scope");
            var variablesArgs = new VariablesArguments();
            variablesArgs.setVariablesReference(argScope.getVariablesReference());
            var variables = launcher.getRemoteProxy().variables(variablesArgs).join().getVariables();
            var target = ((Supplier<Variable>)() -> {
                for (var variable : variables) {
                    if (variable.getName().equals("n")) {
                        return variable;
                    }
                }
                return null;
            }).get();
            assertNotNull(target, "got expected variable");
            assertEquals(target.getValue(), "42.0");

            var continueArgs = new ContinueArguments();
            continueArgs.setThreadId(threadID);
            launcher.getRemoteProxy().continue_(continueArgs);

            
            requestThread.join();
            var disconnectArgs = new DisconnectArguments();
            launcher.getRemoteProxy().disconnect(disconnectArgs).join();
            //socket.close(); // how to let launcher know we want to do this?
        }
        finally {
            dockerClient.stopContainerCmd(container.getId()).exec();
        }
    }

    class MockClient implements IDebugProtocolClient {
        public void breakpoint(BreakpointEventArguments args) {

        }
        public void continued(ContinuedEventArguments args) {

        }
        public void exited(ExitedEventArguments args) {

        }
        public void initialized() {
            
        }
        public void loadedSource(LoadedSourceEventArguments args) {

        }
        public void module(ModuleEventArguments args) {

        }
        public void output(OutputEventArguments args) {

        }
        public void process(ProcessEventArguments args) {

        }

        public Consumer<StoppedEventArguments> stopped_handler = null;
        public void stopped(StoppedEventArguments args) {
            if (stopped_handler != null) {
                stopped_handler.accept(args);
            }
        }

        public void terminated(TerminatedEventArguments args) {

        }
        public void thread(ThreadEventArguments args) {

        }
    }
}
