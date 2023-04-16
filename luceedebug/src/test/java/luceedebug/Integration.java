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

    @Test
    void stepping_works_as_expected_on_singleline_statement_with_many_subexpressions() throws Throwable {
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
                .setBreakpoints(dapServer, "/var/www/a.cfm", 6)
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

            final var threadID = doWithStoppedEventFuture(dapClient, () -> {
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

            doWithStoppedEventFuture(
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

            doWithStoppedEventFuture(
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

            doWithStoppedEventFuture(
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

            doWithStoppedEventFuture(
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

            doWithStoppedEventFuture(
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

            doWithStoppedEventFuture(
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

            doWithStoppedEventFuture(
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
                .setBreakpoints(dapServer, "/var/www/a.cfm", 10)
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

            //
            // we use stepIn/stepOver arbitrarly, for our purposes in this
            //

            final var threadID = doWithStoppedEventFuture(
                dapClient,
                () -> requestThreadToBeBlockedByBreakpoint.start()
            ).get(1000, TimeUnit.MILLISECONDS).getThreadId();

            {
                doWithStoppedEventFuture(
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
                doWithStoppedEventFuture(
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
                doWithStoppedEventFuture(
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
                doWithStoppedEventFuture(
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
                doWithStoppedEventFuture(
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

                doWithStoppedEventFuture(
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
                doWithStoppedEventFuture(
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

    @Test
    void evaluates_an_expression() throws Throwable {
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
