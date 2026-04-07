package luceedebug;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.jsonrpc.util.ToStringBuilder;

import luceedebug.coreinject.NativeDebuggerListener;
import luceedebug.coreinject.NativeLuceeVm;
import luceedebug.generated.Constants;
import luceedebug.strong.CanonicalServerAbsPath;
import luceedebug.strong.RawIdePath;

public class DapServer implements IDebugProtocolServer {
    private final ILuceeVm luceeVm_;
    private final Config config_;
    private ArrayList<IPathTransform> pathTransforms = new ArrayList<>();
    private boolean evaluationEnabled = true;

    // for dev, system.out was fine, in some containers, others totally suppress it and it doesn't even
    // end up in log files.
    // this is all jacked up, on runwar builds it spits out two lines per call to `logger.info(...)` message, the first one being [ERROR] which is not right
    private static final Logger logger = Logger.getLogger("luceedebug");

    // Static reference for shutdown within same classloader
    private static volatile ServerSocket activeServerSocket;

    // System property key for tracking the server socket across classloaders (OSGi bundle reload)
    private static final String SOCKET_PROPERTY = "luceedebug.dap.serverSocket";
    
    private String applyPathTransformsIdeToCf(String s) {
        for (var transform : pathTransforms) {
            var result = transform.ideToServer(s);
            if (result.isPresent()) {
                return Config.canonicalizeFileName(result.get());
            }
        }

        // no transform matched, but still needs canonicalization
        return Config.canonicalizeFileName(s);
    }
    
    /**
     * n.b. do _not_ canonicalize when sending back to IDE
     */
    private String applyPathTransformsServerToIde(String s) {
        for (var transform : pathTransforms) {
            var result = transform.serverToIde(s);
            if (result.isPresent()) {
                return result.get();
            }
        }

        // no match
        return s;
    }

    private IDebugProtocolClient clientProxy_;

    private DapServer(ILuceeVm luceeVm, Config config) {
        this.luceeVm_ = luceeVm;
        this.config_ = config;

        this.luceeVm_.registerStepEventCallback(threadID -> {
            final var i32_threadID = (int)(long)threadID;
            var event = new StoppedEventArguments();
            event.setReason("step");
            event.setThreadId(i32_threadID);
            clientProxy_.stopped(event);
        });

        this.luceeVm_.registerBreakpointEventCallback((threadID, bpID) -> {
            final int i32_threadID = (int)(long)threadID;
            var event = new StoppedEventArguments();
            event.setReason("breakpoint");
            event.setThreadId(i32_threadID);
            event.setHitBreakpointIds(new Integer[] { bpID.get() });
            clientProxy_.stopped(event);
        });

        this.luceeVm_.registerBreakpointsChangedCallback((bpChangedEvent) -> {
            for (var newBreakpoint : bpChangedEvent.newBreakpoints) {
                var bpEvent = new BreakpointEventArguments();
                bpEvent.setBreakpoint(map_cfBreakpoint_to_lsp4jBreakpoint(newBreakpoint));
                bpEvent.setReason("new");
                clientProxy_.breakpoint(bpEvent);
            }

            for (var changedBreakpoint : bpChangedEvent.changedBreakpoints) {
                var bpEvent = new BreakpointEventArguments();
                bpEvent.setBreakpoint(map_cfBreakpoint_to_lsp4jBreakpoint(changedBreakpoint));
                bpEvent.setReason("changed");
                clientProxy_.breakpoint(bpEvent);
            }

            for (var oldBreakpointID : bpChangedEvent.deletedBreakpointIDs) {
                var bpEvent = new BreakpointEventArguments();
                var bp = new Breakpoint();
                bp.setId(oldBreakpointID);
                bpEvent.setBreakpoint(bp);
                bpEvent.setReason("removed");
                clientProxy_.breakpoint(bpEvent);
            }
        });

        // Register native breakpoint callback (Lucee7+ native suspend)
        // Uses Java thread ID directly since native suspend doesn't go through JDWP
        this.luceeVm_.registerNativeBreakpointEventCallback((javaThreadId, label) -> {
            // Use Java thread ID directly as DAP thread ID for native breakpoints
            // This is different from JDWP breakpoints which use JDWP thread IDs
            final int i32_threadID = (int)(long)javaThreadId;
            var event = new StoppedEventArguments();
            event.setReason("breakpoint");
            event.setThreadId(i32_threadID);
            // Set label as description if provided (from programmatic breakpoint("label") calls)
            if (label != null && !label.isEmpty()) {
                event.setDescription(label);
            }
            clientProxy_.stopped(event);
            Log.debug("Stopped event sent: thread=" + javaThreadId + (label != null ? " label=" + label : ""));
        });

        // Register native exception callback (Lucee7+ uncaught exception)
        this.luceeVm_.registerExceptionEventCallback(javaThreadId -> {
            final int i32_threadID = (int)(long)javaThreadId;
            var event = new StoppedEventArguments();
            event.setReason("exception");
            event.setThreadId(i32_threadID);
            // Get exception details for the description
            Throwable ex = luceeVm_.getExceptionForThread(javaThreadId);
            if (ex != null) {
                event.setDescription(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                event.setText(ex.getMessage());
            }
            clientProxy_.stopped(event);
            Log.debug("Sent DAP stopped event for exception, thread=" + javaThreadId + (ex != null ? " exception=" + ex.getClass().getName() : ""));
        });

        // Register native pause callback (user clicked pause button)
        this.luceeVm_.registerPauseEventCallback(javaThreadId -> {
            final int i32_threadID = (int)(long)javaThreadId;
            var event = new StoppedEventArguments();
            event.setReason("pause");
            event.setThreadId(i32_threadID);
            clientProxy_.stopped(event);
            Log.debug("Sent DAP stopped event for pause, thread=" + javaThreadId);
        });
    }

    static class DapEntry {
        public final DapServer server;
        public final Launcher<IDebugProtocolClient> launcher;
        private DapEntry(DapServer server, Launcher<IDebugProtocolClient> launcher) {
            this.server = server;
            this.launcher = launcher;
        }
    }

    static public DapEntry createForSocket(ILuceeVm luceeVm, Config config, String host, int port) {
        // Log immediately to confirm we entered the method
        System.out.println("[luceedebug] createForSocket entered: host=" + host + ", port=" + port);

        // Shut down any existing server first (handles extension reinstall within same classloader)
        shutdown();

        ServerSocket server = null;
        try {
            System.out.println("[luceedebug] Creating ServerSocket...");
            server = new ServerSocket();
            var addr = new InetSocketAddress(host, port);
            server.setReuseAddress(true);

            System.out.println("[luceedebug] Binding to " + host + ":" + port);
            logger.finest("binding cf dap server socket on " + host + ":" + port);

            // Try to bind, with retries for port in use (OSGi bundle reload race condition)
            int maxRetries = 5;
            java.net.BindException lastBindError = null;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    server.bind(addr);
                    lastBindError = null;
                    break;
                } catch (java.net.BindException e) {
                    lastBindError = e;
                    if (attempt < maxRetries) {
                        Log.info("Port " + port + " in use, retrying in 1s (attempt " + attempt + "/" + maxRetries + ")");
                        try { java.lang.Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                    }
                }
            }
            if (lastBindError != null) {
                throw lastBindError;
            }
            activeServerSocket = server;
            // Store in system properties so it survives classloader changes (OSGi bundle reload)
            System.getProperties().put(SOCKET_PROPERTY, server);

            System.out.println("[luceedebug] DAP server socket bound successfully on " + host + ":" + port);
            logger.finest("dap server socket bind OK");

            while (true) {
                System.out.println("[luceedebug] Waiting for DAP client connection on " + host + ":" + port + "...");
                System.out.println("[luceedebug] ServerSocket state: bound=" + server.isBound() + ", closed=" + server.isClosed() + ", localPort=" + server.getLocalPort());
                logger.finest("listening for inbound debugger connection on " + host + ":" + port + "...");

                System.out.println("[luceedebug] Calling server.accept()...");
                var socket = server.accept();
                System.out.println("[luceedebug] accept() returned!");
                var clientAddr = socket.getInetAddress().getHostAddress();
                var clientPort = socket.getPort();

                Log.info("DAP client connected from " + clientAddr + ":" + clientPort);
                logger.finest("accepted debugger connection from " + clientAddr + ":" + clientPort);

                // Mark DAP client as connected - enables breakpoint() BIF to suspend (native mode only)
                if (luceeVm instanceof NativeLuceeVm) {
                    luceedebug.coreinject.NativeDebuggerListener.setDapClientConnected(true);
                }

                try {
                    var rawIn = socket.getInputStream();
                    var rawOut = socket.getOutputStream();
                    var dapEntry = create(luceeVm, config, rawIn, rawOut);
                    // Enable DAP output for this client
                    Log.setDapClient(dapEntry.server.clientProxy_);
                    var future = dapEntry.launcher.startListening();
                    try {
                        future.get(); // block until the connection closes
                    } catch (Exception e) {
                        Log.error("Launcher future exception", e);
                    }
                    Log.debug("DAP client disconnected from " + clientAddr + ":" + clientPort);
                } catch (Exception e) {
                    Log.error("DAP client error: " + e.getClass().getName(), e);
                } finally {
                    // Clear DAP client FIRST to avoid broken pipe when setDapClientConnected logs
                    Log.setDapClient(null);
                    // Mark DAP client as disconnected - disables breakpoint() BIF suspension (native mode only)
                    if (luceeVm instanceof NativeLuceeVm) {
                        luceedebug.coreinject.NativeDebuggerListener.setDapClientConnected(false);
                    }
                    try { socket.close(); } catch (Exception ignored) {}
                    System.out.println("[luceedebug] Client socket closed, returning to accept loop");
                }

                logger.finest("debugger connection closed");
            }
        }
        catch (java.net.SocketException e) {
            // Expected when shutdown() closes the socket
            System.out.println("[luceedebug] DAP server SocketException: " + e.getMessage());
            Log.info("DAP server socket closed");
            return null;
        }
        catch (Throwable e) {
            System.out.println("[luceedebug] DAP server fatal error: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.out);
            e.printStackTrace();
            System.exit(1);
            return null;
        }
        finally {
            System.out.println("[luceedebug] DAP server finally block executing");
            // Only clear if we're still the active server (avoid race with new server starting)
            if (activeServerSocket == server) {
                activeServerSocket = null;
                System.getProperties().remove(SOCKET_PROPERTY);
            }
        }
    }

    /**
     * Shutdown the DAP server. Called on extension uninstall/reinstall.
     * Uses System.getProperties() to find socket from previous classloaders.
     * Uses reflection to avoid OSGi classloader identity issues.
     */
    public static void shutdown() {
        // Log who's calling shutdown with a stack trace
        System.out.println("[luceedebug] DapServer.shutdown() called");
        System.out.println("[luceedebug] shutdown() caller stack trace:");
        for (StackTraceElement ste : java.lang.Thread.currentThread().getStackTrace()) {
            System.out.println("[luceedebug]   " + ste);
        }
        System.out.flush();

        // Try to get socket from JVM-wide properties (survives classloader changes)
        Object storedSocket = System.getProperties().get(SOCKET_PROPERTY);
        if (storedSocket != null) {
            // Use reflection - instanceof may fail across OSGi classloaders
            System.out.println("[luceedebug] shutdown() - found socket in system properties, closing via reflection...");
            Log.debug("shutdown() - found socket in system properties, closing via reflection...");
            try {
                java.lang.reflect.Method closeMethod = storedSocket.getClass().getMethod("close");
                closeMethod.invoke(storedSocket);
                System.out.println("[luceedebug] shutdown() - socket closed");
                Log.debug("shutdown() - socket closed");
            } catch (Exception e) {
                System.out.println("[luceedebug] shutdown() - socket close error: " + e);
                Log.error("shutdown() - socket close error", e);
            }
            System.getProperties().remove(SOCKET_PROPERTY);
        } else {
            System.out.println("[luceedebug] shutdown() - no socket in system properties");
            Log.debug("shutdown() - no socket in system properties");
        }

        // Also close our local static reference if set (same classloader case)
        if (activeServerSocket != null) {
            System.out.println("[luceedebug] shutdown() - closing activeServerSocket");
            try {
                activeServerSocket.close();
            } catch (Exception ignored) {}
            activeServerSocket = null;
        }
    }

    static public DapEntry create(ILuceeVm luceeVm, Config config, InputStream in, OutputStream out) {
        var server = new DapServer(luceeVm, config);
        var serverLauncher = DSPLauncher.createServerLauncher(server, in, out);
        server.clientProxy_ = serverLauncher.getRemoteProxy();
        return new DapEntry(server, serverLauncher);
    }

    @Override
    public CompletableFuture<Capabilities> initialize(InitializeRequestArguments args) {
        Log.debug("initialize() called with args: " + args);
        var c = new Capabilities();
        c.setSupportsEvaluateForHovers(true);
        c.setSupportsConfigurationDoneRequest(true);
        c.setSupportsSingleThreadExecutionRequests(true); // but, vscode does not (from the stack frame panel at least?)

        c.setSupportsConditionalBreakpoints(true);
        c.setSupportsHitConditionalBreakpoints(false); // still shows UI for it though
        c.setSupportsLogPoints(false); // still shows UI for it though

        // Native-mode-only capabilities (require Lucee 7.1+ DebuggerRegistry)
        // Also check if debugger is actually enabled (LUCEE_DAP_BREAKPOINT not set to false)
        boolean isNativeMode = luceeVm_ instanceof NativeLuceeVm && EnvUtil.isDebuggerEnabled();

        // Exception breakpoint filters - only supported in native mode with debugger enabled
        if (isNativeMode) {
            var uncaughtFilter = new ExceptionBreakpointsFilter();
            uncaughtFilter.setFilter("uncaught");
            uncaughtFilter.setLabel("Uncaught Exceptions");
            uncaughtFilter.setDescription("Break when an exception is not caught by a try/catch block");
            c.setExceptionBreakpointFilters(new ExceptionBreakpointsFilter[] { uncaughtFilter });
        }
        c.setSupportsExceptionInfoRequest(isNativeMode);
        c.setSupportsBreakpointLocationsRequest(isNativeMode);
        c.setSupportsSetVariable(isNativeMode);
        c.setSupportsCompletionsRequest(isNativeMode);
        c.setSupportsFunctionBreakpoints(isNativeMode);

        Log.debug("Returning capabilities (nativeMode=" + isNativeMode + ") with exceptionBreakpointFilters: " + java.util.Arrays.toString(c.getExceptionBreakpointFilters()));

        return CompletableFuture.completedFuture(c);
    }

    /**
     * https://github.com/softwareCobbler/luceedebug/issues/50
     */
    @Override
    public CompletableFuture<SourceResponse> source(SourceArguments args) {
        final var exceptionalResult = new CompletableFuture<SourceResponse>();
        final var error = new ResponseError(ResponseErrorCode.MethodNotFound, "'source' requests are not supported", null);
        exceptionalResult.completeExceptionally(new ResponseErrorException(error));
        return exceptionalResult;
    }

    private IPathTransform mungeOnePathTransform(Map<?,?> map) {
        var maybeIdePrefix = map.get("idePrefix");

        // cfPrefix is deprecated in favor of serverPrefix
        var maybeServerPrefix = map.containsKey("cfPrefix") ? map.get("cfPrefix") : map.get("serverPrefix");

        if (maybeServerPrefix instanceof String && maybeIdePrefix instanceof String) {
            return new PrefixPathTransform((String)maybeIdePrefix, (String)maybeServerPrefix);
        }
        else {
            return null;
        }
    }

    private ArrayList<IPathTransform> tryMungePathTransforms(Object maybeNull_val) {
        final var result = new ArrayList<IPathTransform>();
        if (maybeNull_val instanceof List) {
            for (var e : ((List<?>)maybeNull_val)) {
                if (e instanceof Map) {
                    var maybeNull_result = mungeOnePathTransform((Map<?,?>)e);
                    if (maybeNull_result != null) {
                        result.add(maybeNull_result);
                    }
                }
            }
            return result;
        }
        else if (maybeNull_val instanceof Map) {
            var maybeNull_result = mungeOnePathTransform((Map<?,?>)maybeNull_val);
            if (maybeNull_result != null) {
                result.add(maybeNull_result);
            }
        }
        else {
            // no-op, leave the list empty
        }
        return result;
    }

    private boolean getBoolOrFalseIfNonBool(Object obj) {
        return getAsBool(obj, false);
    }

    private boolean getAsBool(Object obj, boolean defaultValue) {
        return obj instanceof Boolean ? ((Boolean)obj) : defaultValue;
    }

    @Override
    public CompletableFuture<Void> attach(Map<String, Object> args) {
        // Configure logging from launch.json (before other logging)
        configureLogging(args);

        // Log version info first
        String luceeVersion = getLuceeVersion();
        Log.info("luceedebug " + Constants.version + " connected to Lucee " + luceeVersion);

        // Validate secret from launch.json
        if (!validateSecret(args)) {
            var result = new CompletableFuture<Void>();
            var error = new ResponseError(ResponseErrorCode.InvalidRequest, "Invalid or missing secret", null);
            result.completeExceptionally(new ResponseErrorException(error));
            return result;
        }

        pathTransforms = tryMungePathTransforms(args.get("pathTransforms"));

        config_.setStepIntoUdfDefaultValueInitFrames(getBoolOrFalseIfNonBool(args.get("stepIntoUdfDefaultValueInitFrames")));

        evaluationEnabled = getAsBool(args.get("evaluation"), true);
        if (!evaluationEnabled) {
            Log.info("Expression evaluation disabled");
        }

        clientProxy_.initialized();

        if (pathTransforms.size() == 0) {
            Log.info("No path transforms configured");
        }
        else {
            for (var transform : pathTransforms) {
                Log.info(transform.asTraceString());
                // Set base path for shortening paths in logs (use first transform's server prefix)
                if (transform instanceof PrefixPathTransform) {
                    Config.setBasePath(((PrefixPathTransform)transform).getServerPrefix());
                }
            }
        }

        return CompletableFuture.completedFuture(null);
    }

    // Track whether secret has been validated for this session
    private boolean secretValidated = false;

    /**
     * Validate the secret from launch.json.
     * Works for both native mode (via ExtensionActivator) and agent mode (direct validation).
     *
     * @param args The attach arguments containing the secret
     * @return true if secret is valid, false otherwise
     */
    private boolean validateSecret(Map<String, Object> args) {
        Object secretObj = args.get("secret");
        String clientSecret = (secretObj instanceof String) ? ((String) secretObj).trim() : null;

        if (clientSecret == null || clientSecret.isEmpty()) {
            Log.error("No secret provided in launch.json");
            return false;
        }

        // Try native mode first (Lucee 7.1+ extension)
        // The class exists in both modes (shadow JAR), but isNativeModeActive() returns true
        // only if the extension was actually loaded by Lucee's startup-hook mechanism.
        try {
            Class<?> activatorClass = Class.forName("luceedebug.extension.ExtensionActivator");
            java.lang.reflect.Method isNativeMethod = activatorClass.getMethod("isNativeModeActive");
            Boolean isNative = (Boolean) isNativeMethod.invoke(null);

            if (isNative) {
                // We're in native mode - use ExtensionActivator to register
                java.lang.reflect.Method registerMethod = activatorClass.getMethod("registerListener", String.class);
                Boolean registered = (Boolean) registerMethod.invoke(null, clientSecret);
                if (registered) {
                    secretValidated = true;
                    return true;
                } else {
                    Log.error("Failed to register debugger - invalid secret");
                    return false;
                }
            }
            // Not in native mode, fall through to agent mode validation
        } catch (ClassNotFoundException e) {
            // Class not found - shouldn't happen with shadow JAR but fall through anyway
        } catch (Exception e) {
            Log.error("Error checking native mode status", e);
            return false;
        }

        // Agent mode - validate secret directly
        String expectedSecret = EnvUtil.getDebuggerSecret();
        if (expectedSecret == null) {
            // No secret configured on server - allow any secret for backwards compatibility?
            // No - require secret to be set for security
            Log.error("LUCEE_DAP_SECRET not set on server");
            return false;
        }

        if (!expectedSecret.equals(clientSecret)) {
            Log.error("Invalid secret");
            return false;
        }

        secretValidated = true;
        return true;
    }

    /**
     * Helper to reject requests when not authorized.
     * Returns a failed CompletableFuture with appropriate error message.
     */
    private <T> CompletableFuture<T> notAuthorized() {
        var result = new CompletableFuture<T>();
        var error = new ResponseError(ResponseErrorCode.InvalidRequest, "Not authorized - call 'attach' with valid secret first", null);
        result.completeExceptionally(new ResponseErrorException(error));
        return result;
    }

    /**
     * Simple boolean conversion without depending on Lucee's Cast utility.
     * Handles Boolean, String ("true"/"false"), and defaults.
     */
    private static boolean toBooleanValue(Object obj, boolean defaultValue) {
        if (obj == null) return defaultValue;
        if (obj instanceof Boolean) return (Boolean) obj;
        if (obj instanceof String) {
            String s = ((String) obj).toLowerCase().trim();
            if ("true".equals(s) || "yes".equals(s) || "1".equals(s)) return true;
            if ("false".equals(s) || "no".equals(s) || "0".equals(s)) return false;
        }
        return defaultValue;
    }

    /**
     * Configure logging from launch.json settings.
     * Supports: logColor (boolean), logLevel (error|info|debug), logExceptions (boolean), logSystemOutput (boolean)
     */
    private void configureLogging(Map<String, Object> args) {
        // logColor - default true
        Log.setColorLogs(toBooleanValue(args.get("logColor"), true));

        // logLevel - error, info, debug
        Object logLevel = args.get("logLevel");
        if (logLevel instanceof String) {
            String level = ((String) logLevel).toLowerCase();
            switch (level) {
                case "error":
                    Log.setLogLevel(Log.LogLevel.ERROR);
                    break;
                case "debug":
                    Log.setLogLevel(Log.LogLevel.DEBUG);
                    break;
                case "info":
                default:
                    Log.setLogLevel(Log.LogLevel.INFO);
                    break;
            }
        }

        // logExceptions - default false
        Log.setLogExceptions(toBooleanValue(args.get("logExceptions"), false));

        // consoleOutput - default false (streams System.out/err to debug console)
        // Only available in native mode
        if (luceeVm_ instanceof NativeLuceeVm) {
            NativeDebuggerListener.setConsoleOutput(toBooleanValue(args.get("consoleOutput"), false));
        }
    }

    static final Pattern threadNamePrefixAndDigitSuffix = Pattern.compile("^(.+?)(\\d+)$");

    @Override
    public CompletableFuture<ThreadsResponse> threads() {
        if (!secretValidated) return notAuthorized();

        var lspThreads = new ArrayList<org.eclipse.lsp4j.debug.Thread>();

        for (var threadInfo : luceeVm_.getThreadListing()) {
            var lspThread = new org.eclipse.lsp4j.debug.Thread();
            lspThread.setId((int)threadInfo.id);
            lspThread.setName(threadInfo.name);
            lspThreads.add(lspThread);
        }
        
        // a lot of thread names like "Thread-Foo-1" and "Thread-Foo-12" which we'd like to order in a nice way
        lspThreads.sort((lthread, rthread) -> {
            final var l = lthread.getName().toLowerCase();
            final var r = rthread.getName().toLowerCase();

            final var ml = threadNamePrefixAndDigitSuffix.matcher(l);
            final var mr = threadNamePrefixAndDigitSuffix.matcher(r);

            // "Thread-Foo-1"
            // "Thread-Foo-12"
            // they share a prefix, so ok compare by integer suffix
            if (ml.matches() && mr.matches() && ml.group(1).equals(mr.group(1))) {
                try  {
                    final var intl = Integer.parseInt(ml.group(2));
                    final var intr = Integer.parseInt(mr.group(2));
                    return intl < intr ? -1 : intl == intr ? 0 : 1;
                }
                catch (NumberFormatException e) {
                    // discard, but shouldn't happen
                }
            }

            return l.compareTo(r);
        });

        var response = new ThreadsResponse();
        response.setThreads(lspThreads.toArray(new org.eclipse.lsp4j.debug.Thread[lspThreads.size()]));

        return CompletableFuture.completedFuture(response);
    }

    @Override
    public CompletableFuture<StackTraceResponse> stackTrace(StackTraceArguments args) {
        if (!secretValidated) return notAuthorized();

        var lspFrames = new ArrayList<org.eclipse.lsp4j.debug.StackFrame>();

        for (var cfFrame : luceeVm_.getStackTrace(args.getThreadId())) {
            final var source = new Source();
            String rawPath = cfFrame.getSourceFilePath();
            String transformedPath = applyPathTransformsServerToIde(rawPath);
            Log.debug("stackTrace: raw=" + rawPath + " -> transformed=" + transformedPath);
            source.setPath(transformedPath);

            final var lspFrame = new org.eclipse.lsp4j.debug.StackFrame();
            lspFrame.setId((int)cfFrame.getId());
            lspFrame.setName(cfFrame.getName());
            lspFrame.setLine(cfFrame.getLine());
            lspFrame.setSource(source);

            lspFrames.add(lspFrame);
        }

        var response = new StackTraceResponse();
        response.setStackFrames(lspFrames.toArray(new org.eclipse.lsp4j.debug.StackFrame[lspFrames.size()]));
        response.setTotalFrames(lspFrames.size());

        return CompletableFuture.completedFuture(response);
    }

    @Override
	public CompletableFuture<ScopesResponse> scopes(ScopesArguments args) {
        if (!secretValidated) return notAuthorized();

        var scopes = new ArrayList<Scope>();
        for (var entity : luceeVm_.getScopes(args.getFrameId())) {
            var scope = new Scope();
            scope.setName(entity.getName());
            scope.setVariablesReference((int)entity.getVariablesReference());
            scope.setIndexedVariables(entity.getIndexedVariables());
            scope.setNamedVariables(entity.getNamedVariables());
            scope.setExpensive(entity.getExpensive());
            scopes.add(scope);
        }
        var result = new ScopesResponse();
        result.setScopes(scopes.toArray(size -> new Scope[size]));
        return CompletableFuture.completedFuture(result);
	}

	@Override
	public CompletableFuture<VariablesResponse> variables(VariablesArguments args) {
        if (!secretValidated) return notAuthorized();

        var variables = new ArrayList<Variable>();
        IDebugEntity[] entities = args.getFilter() == null
            ? luceeVm_.getVariables(args.getVariablesReference())
            : args.getFilter() == VariablesArgumentsFilter.INDEXED
            ? luceeVm_.getIndexedVariables(args.getVariablesReference())
            : args.getFilter() == VariablesArgumentsFilter.NAMED
            ? luceeVm_.getNamedVariables(args.getVariablesReference())
            : new IDebugEntity[0];

        for (var entity : entities) {
            var variable = new Variable();
            variable.setName(entity.getName());
            variable.setVariablesReference((int)entity.getVariablesReference());
            variable.setIndexedVariables(entity.getIndexedVariables());
            variable.setNamedVariables(entity.getNamedVariables());
            variable.setValue(entity.getValue());
            variables.add(variable);
        }
        var result = new VariablesResponse();
        result.setVariables(variables.toArray(size -> new Variable[size]));
        return CompletableFuture.completedFuture(result);
	}

	@Override
	public CompletableFuture<SetVariableResponse> setVariable(SetVariableArguments args) {
		Log.debug("setVariable() called: variablesReference=" + args.getVariablesReference() + ", name=" + args.getName() + ", value=" + args.getValue());

		if (!secretValidated) return notAuthorized();

		return luceeVm_
			.setVariable(args.getVariablesReference(), args.getName(), args.getValue(), 0)
			.collapse(
				errMsg -> {
					Log.info("setVariable() - error: " + errMsg);
					var exceptionalResult = new CompletableFuture<SetVariableResponse>();
					var error = new ResponseError(ResponseErrorCode.InternalError, errMsg, null);
					exceptionalResult.completeExceptionally(new ResponseErrorException(error));
					return exceptionalResult;
				},
				someResult -> {
					return someResult.collapse(
						bridgeObj -> {
							// Complex object returned
							IDebugEntity value = bridgeObj.maybeNull_asValue(args.getName());
							var response = new SetVariableResponse();
							if (value == null) {
								response.setValue("<?>");
								response.setVariablesReference(0);
							} else {
								response.setValue(value.getValue());
								response.setVariablesReference((int) value.getVariablesReference());
								response.setNamedVariables(bridgeObj.getNamedVariablesCount());
								response.setIndexedVariables(bridgeObj.getIndexedVariablesCount());
							}
							Log.debug("setVariable() - success (object): value=" + response.getValue() + ", ref=" + response.getVariablesReference());
							return CompletableFuture.completedFuture(response);
						},
						stringValue -> {
							// Simple value returned
							var response = new SetVariableResponse();
							response.setValue(stringValue);
							response.setVariablesReference(0);
							Log.debug("setVariable() - success (simple): value=" + stringValue);
							return CompletableFuture.completedFuture(response);
						}
					);
				}
			);
	}

    @Override
    public CompletableFuture<SetBreakpointsResponse> setBreakpoints(SetBreakpointsArguments args) {
        if (!secretValidated) return notAuthorized();

        final var idePath = new RawIdePath(args.getSource().getPath());
        final var serverAbsPath = new CanonicalServerAbsPath(applyPathTransformsIdeToCf(args.getSource().getPath()));

        logger.finest("bp for " + idePath.get() + " -> " + serverAbsPath.get());

        final int size = args.getBreakpoints().length;
        final int[] lines = new int[size];
        final String[] exprs = new String[size];
        for (int i = 0; i < size; ++i) {
            lines[i] = args.getBreakpoints()[i].getLine();
            exprs[i] = args.getBreakpoints()[i].getCondition();
        }

        var result = new ArrayList<Breakpoint>();
        for (IBreakpoint bp : luceeVm_.bindBreakpoints(idePath, serverAbsPath, lines, exprs)) {
            result.add(map_cfBreakpoint_to_lsp4jBreakpoint(bp));
        }

        var response = new SetBreakpointsResponse();
        response.setBreakpoints(result.toArray(len -> new Breakpoint[len]));

        return CompletableFuture.completedFuture(response);
    }

    private Breakpoint map_cfBreakpoint_to_lsp4jBreakpoint(IBreakpoint cfBreakpoint) {
        var bp = new Breakpoint();
        bp.setLine(cfBreakpoint.getLine());
        bp.setId(cfBreakpoint.getID());
        bp.setVerified(cfBreakpoint.getIsBound());
        return bp;
    }

    @Override
    public CompletableFuture<BreakpointLocationsResponse> breakpointLocations(BreakpointLocationsArguments args) {
        if (!secretValidated) return notAuthorized();

        var response = new BreakpointLocationsResponse();

        // Only works in native mode with NativeLuceeVm
        if (!(luceeVm_ instanceof luceedebug.coreinject.NativeLuceeVm)) {
            response.setBreakpoints(new BreakpointLocation[0]);
            return CompletableFuture.completedFuture(response);
        }

        var nativeVm = (luceedebug.coreinject.NativeLuceeVm) luceeVm_;
        String serverPath = applyPathTransformsIdeToCf(args.getSource().getPath());
        int[] executableLines = nativeVm.getExecutableLines(serverPath);

        // Filter to requested line range
        int startLine = args.getLine();
        int endLine = args.getEndLine() != null ? args.getEndLine() : startLine;

        var locations = new java.util.ArrayList<BreakpointLocation>();
        for (int line : executableLines) {
            if (line >= startLine && line <= endLine) {
                var loc = new BreakpointLocation();
                loc.setLine(line);
                locations.add(loc);
            }
        }

        response.setBreakpoints(locations.toArray(new BreakpointLocation[0]));
        return CompletableFuture.completedFuture(response);
    }

    /**
     * We don't really support this, but not sure how to say that; there doesn't seem to be a "supports exception breakpoints"
     * flag in the init response? vscode always sends this?
     * 
     * in cppdap, it didn't (against the same client code), so there is likely some
     * initialization configuration that can set whether the client sends this or not
     * 
     * Seems adding support for "configuration done" means clients don't need to send this request
     * https://microsoft.github.io/debug-adapter-protocol/specification#Events_Initialized
     * 
     * @unsupported
     */
    @Override
	public CompletableFuture<SetExceptionBreakpointsResponse> setExceptionBreakpoints(SetExceptionBreakpointsArguments args) {
		if (!secretValidated) return notAuthorized();

		// Check if "uncaught" is in the filters
		String[] filters = args.getFilters();
		Log.debug("setExceptionBreakpoints: filters=" + java.util.Arrays.toString(filters));
		boolean breakOnUncaught = false;
		if (filters != null) {
			for (String filter : filters) {
				if ("uncaught".equals(filter)) {
					breakOnUncaught = true;
					break;
				}
			}
		}
		// Only works in native mode - NativeDebuggerListener doesn't exist in agent mode
		if (luceeVm_ instanceof NativeLuceeVm) {
			NativeDebuggerListener.setBreakOnUncaughtExceptions(breakOnUncaught);
		}
		return CompletableFuture.completedFuture(new SetExceptionBreakpointsResponse());
	}

	/**
	 * Handle function breakpoints - break when a function with a given name is called.
	 * Supports:
	 * - Simple names: "onRequestStart" matches any function with that name
	 * - Qualified names: "User.save" matches save() in User.cfc only
	 * - Wildcards: "on*" matches onRequestStart, onError, etc.
	 * - Conditions: "onRequestStart" with condition "cgi.script_name contains '/api/'"
	 */
	@Override
	public CompletableFuture<SetFunctionBreakpointsResponse> setFunctionBreakpoints(
			SetFunctionBreakpointsArguments args) {
		if (!secretValidated) return notAuthorized();

		// Only works in native mode - NativeDebuggerListener doesn't exist in agent mode
		if (!(luceeVm_ instanceof NativeLuceeVm)) {
			return CompletableFuture.completedFuture(new SetFunctionBreakpointsResponse());
		}

		FunctionBreakpoint[] bps = args.getBreakpoints();
		Log.debug("setFunctionBreakpoints: " + (bps != null ? bps.length : 0) + " breakpoints");

		if (bps == null || bps.length == 0) {
			NativeDebuggerListener.clearFunctionBreakpoints();
			return CompletableFuture.completedFuture(new SetFunctionBreakpointsResponse());
		}

		String[] names = new String[bps.length];
		String[] conditions = new String[bps.length];

		for (int i = 0; i < bps.length; i++) {
			names[i] = bps[i].getName();
			conditions[i] = bps[i].getCondition();
			Log.debug("  Function breakpoint: " + names[i] +
				(conditions[i] != null ? " condition=" + conditions[i] : ""));
		}

		NativeDebuggerListener.setFunctionBreakpoints(names, conditions);

		// Build response - mark all as verified (we can't validate until runtime)
		Breakpoint[] result = new Breakpoint[bps.length];
		for (int i = 0; i < bps.length; i++) {
			Breakpoint bp = new Breakpoint();
			bp.setId(i + 1);
			bp.setVerified(true);
			bp.setMessage("Function breakpoint: " + names[i]);
			result[i] = bp;
		}

		SetFunctionBreakpointsResponse response = new SetFunctionBreakpointsResponse();
		response.setBreakpoints(result);
		return CompletableFuture.completedFuture(response);
	}

	/**
	 * Returns exception details when stopped due to an exception.
	 * VSCode calls this after receiving a stopped event with reason="exception".
	 */
	@Override
	public CompletableFuture<ExceptionInfoResponse> exceptionInfo(ExceptionInfoArguments args) {
		if (!secretValidated) return notAuthorized();

		Log.debug("exceptionInfo() called for thread " + args.getThreadId());
		Throwable ex = luceeVm_.getExceptionForThread(args.getThreadId());
		var response = new ExceptionInfoResponse();
		if (ex != null) {
			response.setExceptionId(ex.getClass().getName());
			response.setDescription(ex.getMessage());
			response.setBreakMode(ExceptionBreakMode.UNHANDLED);
			// Build detailed stack trace
			var details = new ExceptionDetails();
			// Include detail if available (Lucee PageException has getDetail())
			String message = ex.getMessage();
			String detail = ExceptionUtil.getDetail(ex);
			if (detail != null && !detail.isEmpty()) {
				message = message + "\n\nDetail: " + detail;
			}
			details.setMessage(message);
			details.setTypeName(ex.getClass().getName());
			details.setStackTrace(ExceptionUtil.getCfmlStackTraceOrFallback(ex));
			// Include inner exception if present
			if (ex.getCause() != null) {
				var inner = new ExceptionDetails();
				inner.setMessage(ex.getCause().getMessage());
				inner.setTypeName(ex.getCause().getClass().getName());
				details.setInnerException(new ExceptionDetails[] { inner });
			}
			response.setDetails(details);
			Log.debug("exceptionInfo() returning: " + ex.getClass().getName() + " - " + ex.getMessage());
		} else {
			response.setExceptionId("unknown");
			response.setDescription("No exception information available");
			response.setBreakMode(ExceptionBreakMode.UNHANDLED);
			Log.debug("exceptionInfo() - no exception found for thread");
		}
		return CompletableFuture.completedFuture(response);
	}

    /**
     * Pause a running thread. In native mode, this is cooperative - the thread
     * will pause at the next CFML instrumentation point (next line of CFML code).
     * Won't pause threads stuck in pure Java code (JDBC, HTTP, sleep, etc.).
     */
	@Override
	public CompletableFuture<Void> pause(PauseArguments args) {
		if (!secretValidated) return notAuthorized();

		long threadId = args.getThreadId();
		Log.info("pause() called for thread " + threadId);
		// Thread ID 0 means "pause all threads" - this happens when user clicks
		// pause button without a specific thread selected
		luceeVm_.pause(threadId);
		return CompletableFuture.completedFuture(null);
	}

    @Override
	public CompletableFuture<Void> disconnect(DisconnectArguments args) {
        Log.info("DAP client disconnected");
        luceeVm_.clearAllBreakpoints();
        luceeVm_.continueAll();
		return CompletableFuture.completedFuture(null);
	}

    @Override
	public CompletableFuture<ContinueResponse> continue_(ContinueArguments args) {
		if (!secretValidated) return notAuthorized();
		luceeVm_.continue_(args.getThreadId());
        return CompletableFuture.completedFuture(new ContinueResponse());
	}

    @Override
	public CompletableFuture<Void> next(NextArguments args) {
		if (!secretValidated) return notAuthorized();
		luceeVm_.stepOver(args.getThreadId());
        return CompletableFuture.completedFuture(null);
	}

    @Override
	public CompletableFuture<Void> stepIn(StepInArguments args) {
		if (!secretValidated) return notAuthorized();
        luceeVm_.stepIn(args.getThreadId());
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public CompletableFuture<Void> stepOut(StepOutArguments args) {
		if (!secretValidated) return notAuthorized();
        luceeVm_.stepOut(args.getThreadId());
		return CompletableFuture.completedFuture(null);
	}

    @Override
	public CompletableFuture<Void> configurationDone(ConfigurationDoneArguments args) {
		return CompletableFuture.completedFuture(null);
	}

    class DumpArguments {
        private int variablesReference;
        public int getVariablesReference() {
            return variablesReference;
        }
        
        @Override
        public String toString() {
          ToStringBuilder b = new ToStringBuilder(this);
          b.add("variablesReference", this.variablesReference);
          return b.toString();
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (this.getClass() != obj.getClass()) {
                return false;
            }
            DumpArguments other = (DumpArguments) obj;
            if (this.variablesReference != other.variablesReference) {
                return false;
            }
            return true;
        }
    }

    class DumpResponse {
        private String content;
        public String getContent() {
            return content;
        }
        public void setContent(final String htmlDocument) {
            this.content = htmlDocument;
        }
        
        @Override
        public String toString() {
          ToStringBuilder b = new ToStringBuilder(this);
          b.add("content", this.content);
          return b.toString();
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (this.getClass() != obj.getClass()) {
                return false;
            }
            DumpResponse other = (DumpResponse) obj;
            if (!this.content.equals(other.content)) {
                return false;
            }
            return true;
        }
    }

    @JsonRequest
	CompletableFuture<DumpResponse> dump(DumpArguments args) {
        if (!secretValidated) return notAuthorized();
        final var response = new DumpResponse();
        response.setContent(luceeVm_.dump(args.variablesReference));
        return CompletableFuture.completedFuture(response);
	}

    @JsonRequest
	CompletableFuture<DumpResponse> dumpAsJSON(DumpArguments args) {
        if (!secretValidated) return notAuthorized();
        final var response = new DumpResponse();
        response.setContent(luceeVm_.dumpAsJSON(args.variablesReference));
        return CompletableFuture.completedFuture(response);
	}

    @JsonRequest
	CompletableFuture<DumpResponse> getMetadata(DumpArguments args) {
        if (!secretValidated) return notAuthorized();
        final var response = new DumpResponse();
        response.setContent(luceeVm_.getMetadata(args.variablesReference));
        return CompletableFuture.completedFuture(response);
	}

    @JsonRequest
	CompletableFuture<DumpResponse> getApplicationSettings(DumpArguments args) {
        if (!secretValidated) return notAuthorized();
        final var response = new DumpResponse();
        response.setContent(luceeVm_.getApplicationSettings());
        return CompletableFuture.completedFuture(response);
	}

    class DebugBreakpointBindingsResponse {
        /** as we see them on the server, after fs canonicalization */
        private String[] canonicalFilenames;
        /** [original, transformed][] */
        private String[][] breakpoints;
        private String[] pathTransforms;

        public String[] getCanonicalFilenames() {
            return canonicalFilenames;
        }
        public void setCanonicalFilenames(final String[] v) {
            this.canonicalFilenames = v;
        }

        public String[][] getBreakpoints() {
            return breakpoints;
        }
        public void setBreakpoints(final String[][] v) {
            this.breakpoints = v;
        }

        public String[] getPathTransforms() {
            return pathTransforms;
        }
        public void setPathTransforms(String[] v) {
            this.pathTransforms = v;
        }
        
        @Override
        public String toString() {
            ToStringBuilder b = new ToStringBuilder(this);
            b.add("canonicalFilenames", this.canonicalFilenames);
            b.add("breakpoints", this.breakpoints);
            b.add("pathTransforms", this.pathTransforms);
            return b.toString();
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (this.getClass() != obj.getClass()) {
                return false;
            }

            DebugBreakpointBindingsResponse other = (DebugBreakpointBindingsResponse) obj;

            if (this.canonicalFilenames == null) {
                if (other.canonicalFilenames != null) {
                    return false;
                }
            }
            else if (!Arrays.deepEquals(this.canonicalFilenames, other.canonicalFilenames)) {
                return false;
            }

            if (this.breakpoints == null) {
                if (other.breakpoints != null) {
                    return false;
                }
            }
            else if (!Arrays.deepEquals(this.breakpoints, other.breakpoints)) {
                return false;
            }

            if (this.pathTransforms == null) {
                if (other.pathTransforms != null) {
                    return false;
                }
            }
            else if (!Arrays.deepEquals(this.pathTransforms, other.pathTransforms)) {
                return false;
            }

            return true;
        }
    }

    class DebugBreakpointBindingsArguments {
    }


    @JsonRequest
	CompletableFuture<DebugBreakpointBindingsResponse> debugBreakpointBindings(DebugBreakpointBindingsArguments args) {
        if (!secretValidated) return notAuthorized();
        final var response = new DebugBreakpointBindingsResponse();
        response.setCanonicalFilenames(luceeVm_.getTrackedCanonicalFileNames());
        response.setBreakpoints(luceeVm_.getBreakpointDetail());
        
        var transforms = new ArrayList<String>();
        for (var v : this.pathTransforms) {
            transforms.add(v.asTraceString());
        }
        response.setPathTransforms(transforms.toArray(new String[0]));

        return CompletableFuture.completedFuture(response);
	}

    class GetSourcePathArguments {
        private int variablesReference;

        public int getVariablesReference() {
            return variablesReference;
        }
        public void setBreakpoints(int v) {
            this.variablesReference = v;
        }

        @Override
        public String toString() {
            ToStringBuilder b = new ToStringBuilder(this);
            b.add("variablesReference", this.variablesReference);
            return b.toString();
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (this.getClass() != obj.getClass()) {
                return false;
            }

            GetSourcePathArguments other = (GetSourcePathArguments) obj;

            if (this.variablesReference != other.variablesReference) {
                return false;
            }

            return true;
        }
    }

    class GetSourcePathResponse {
        private String path;

        public String getPath() {
            return path;
        }
        public void setPath(String path) {
            this.path = path;
        }

        @Override
        public String toString() {
            ToStringBuilder b = new ToStringBuilder(this);
            b.add("path", this.path);
            return b.toString();
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (this.getClass() != obj.getClass()) {
                return false;
            }

            GetSourcePathResponse other = (GetSourcePathResponse) obj;

            if (!this.path.equals(other.path)) {
                return false;
            }

            return true;
        }
    }

    @JsonRequest
	CompletableFuture<GetSourcePathResponse> getSourcePath(GetSourcePathArguments args) {
        if (!secretValidated) return notAuthorized();
        final var response = new GetSourcePathResponse();
        final var serverPath = luceeVm_.getSourcePathForVariablesRef(args.getVariablesReference());

        if (serverPath != null) {
            response.setPath(applyPathTransformsServerToIde(serverPath));
        }
        else {
            response.setPath(null);
        }

        return CompletableFuture.completedFuture(response);
	}

    static private AtomicLong anonymousID = new AtomicLong();

    public CompletableFuture<EvaluateResponse> evaluate(EvaluateArguments args) {
        if (!secretValidated) return notAuthorized();

        final String expr = args.getExpression();
        final String context = args.getContext(); // "hover", "watch", "repl", or null
        final boolean isHover = "hover".equals(context);

        if (!evaluationEnabled) {
            final var exceptionalResult = new CompletableFuture<EvaluateResponse>();
            final var error = new ResponseError(ResponseErrorCode.InvalidRequest, "evaluation disabled", null);
            exceptionalResult.completeExceptionally(new ResponseErrorException(error));
            return exceptionalResult;
        }

        if (args.getFrameId() == null) {
            if (!isHover) { Log.info("evaluate(\"" + expr + "\") - error: missing frameID"); }
            final var exceptionalResult = new CompletableFuture<EvaluateResponse>();
            final var error = new ResponseError(ResponseErrorCode.InvalidRequest, "missing frameID", null);
            exceptionalResult.completeExceptionally(new ResponseErrorException(error));
            return exceptionalResult;
        }
        else {
            return luceeVm_
                .evaluate(args.getFrameId(), expr)
                .collapse(
                    errMsg -> {
                        if (!isHover) { Log.info("evaluate(\"" + expr + "\") - error: " + errMsg); }
                        final var exceptionalResult = new CompletableFuture<EvaluateResponse>();
                        final var error = new ResponseError(ResponseErrorCode.InternalError, errMsg, null);
                        exceptionalResult.completeExceptionally(new ResponseErrorException(error));
                        return exceptionalResult;
                    },
                    someResult -> {
                        return someResult.collapse(
                            someObj -> {
                                final IDebugEntity value = someObj.maybeNull_asValue("anonymous value " + anonymousID.incrementAndGet());
                                final var response = new EvaluateResponse();
                                if (value == null) {
                                    // some problem, or we tried to get a function from a cfc maybe? this needs work.
                                    Log.info("evaluate(\"" + expr + "\") = ???");
                                    response.setVariablesReference(0);
                                    response.setIndexedVariables(0);
                                    response.setNamedVariables(0);
                                    response.setResult("???");
                                }
                                else {
                                    Log.info("evaluate(\"" + expr + "\") = " + value.getValue());
                                    response.setVariablesReference((int)(long)value.getVariablesReference());
                                    response.setIndexedVariables(value.getIndexedVariables());
                                    response.setNamedVariables(value.getNamedVariables());
                                    // want to see "Struct (4 members)" instead of "anonymous value X"
                                    response.setResult(value.getValue());
                                }
                                return CompletableFuture.completedFuture(response);
                            },
                            string -> {
                                Log.info("evaluate(\"" + expr + "\") = " + string);
                                final var response = new EvaluateResponse();
                                response.setResult(string);
                                return CompletableFuture.completedFuture(response);
                            });
                    }
                );
        }
    }

    @Override
    public CompletableFuture<CompletionsResponse> completions(CompletionsArguments args) {
        if (!secretValidated) return notAuthorized();

        final String text = args.getText();
        final int column = args.getColumn();
        final Integer frameId = args.getFrameId();

        // Parse the text to find what we're completing
        // column is 1-based by default, text up to cursor
        int cursorPos = Math.min(column - 1, text.length());
        String prefix = text.substring(0, cursorPos);

        // Find the start of the current "word" we're completing
        // CFML variables can contain: letters, digits, underscores, dots, brackets
        int wordStart = cursorPos;
        while (wordStart > 0) {
            char c = prefix.charAt(wordStart - 1);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '[' || c == ']' || c == '"' || c == '\'') {
                wordStart--;
            } else {
                break;
            }
        }

        String partialExpr = prefix.substring(wordStart);

        // Get completions from the VM
        CompletionItem[] items = luceeVm_.getCompletions(frameId != null ? frameId : 0, partialExpr);

        // Set start and length on each item so VSCode replaces the partial expression
        // start is 0-based position in the text where replacement begins
        // length is how many characters to replace (the partial expression length)
        for (CompletionItem item : items) {
            item.setStart(wordStart);  // 0-based position where partial expr starts
            item.setLength(partialExpr.length());  // Replace the typed prefix
        }

        CompletionsResponse response = new CompletionsResponse();
        response.setTargets(items);
        return CompletableFuture.completedFuture(response);
    }

    /**
     * Get the Lucee version string (e.g., "7.0.1.7-ALPHA").
     * In agent mode, Lucee classes may not be accessible, so we catch Throwable.
     */
    private static String getLuceeVersion() {
        try {
            lucee.Info info = lucee.loader.engine.CFMLEngineFactory.getInstance().getInfo();
            return info.getVersion().toString();
        } catch (Throwable t) {
            // NoClassDefFoundError is an Error, not Exception - need to catch Throwable
            // This happens in agent mode where Lucee's classloader is separate
            return "unknown";
        }
    }
}
