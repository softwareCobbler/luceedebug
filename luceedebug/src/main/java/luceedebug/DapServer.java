package luceedebug;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.xtext.xbase.lib.Pure;
import org.eclipse.xtext.xbase.lib.util.ToStringBuilder;

public class DapServer implements IDebugProtocolServer {
    private final ILuceeVm luceeVm_;
    private final Config config_;
    private ArrayList<IPathTransform> pathTransforms = new ArrayList<>();

    interface TransformRunner {
        Optional<String> run(IPathTransform transform, String s);
    }
    
    /**
     * runs all the transforms until one matches and produces a result
     * if no transform matches, returns the input string unmodified
     */
    private String applyPathTransforms(String s, TransformRunner runner) {
        for (var transform : pathTransforms) {
            var result = runner.run(transform, s);
            if (result.isPresent()) {
                return config_.canonicalizePath(result.get());
            }
        }
        // no transform matched, but still needs canonicalization
        return config_.canonicalizePath(s);
    }

    private String applyPathTransformsIdeToCf(String s) {
        return applyPathTransforms(
            s,
            (transform, path) -> transform.ideToServer(path)
        );
    }
    
    private String applyPathTransformsCfToIde(String s) {
        return applyPathTransforms(
            s,
            (transform, path) -> transform.serverToIde(path)
        );
    }

    private IDebugProtocolClient clientProxy_;

    private DapServer(ILuceeVm luceeVm, Config config) {
        this.luceeVm_ = luceeVm;
        this.config_ = config;

        this.luceeVm_.registerStepEventCallback(i64_threadID -> {
            final var i32_threadID = (int)(long)i64_threadID;
            var event = new StoppedEventArguments();
            event.setReason("step");
            event.setThreadId(i32_threadID);
            clientProxy_.stopped(event);
        });

        this.luceeVm_.registerBreakpointEventCallback((i64_threadID, i32_bpID) -> {
            final int i32_threadID = (int)(long)i64_threadID;
            var event = new StoppedEventArguments();
            event.setReason("breakpoint");
            event.setThreadId(i32_threadID);
            event.setHitBreakpointIds(new Integer[] { i32_bpID });
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
        try (var server = new ServerSocket()) {
            var addr = new InetSocketAddress(host, port);
            server.setReuseAddress(true);

            System.out.println("[luceedebug] binding cf dap server socket on " + host + ":" + port);

            server.bind(addr);

            System.out.println("[luceedebug] dap server socket bind OK");

            while (true) {
                System.out.println("[luceedebug] listening for inbound debugger connection on " + host + ":" + port + "...");

                var socket = server.accept();

                System.out.println("[luceedebug] accepted debugger connection");

                var dapEntry = create(luceeVm, config, socket.getInputStream(), socket.getOutputStream());
                var future = dapEntry.launcher.startListening();
                future.get(); // block until the connection closes

                System.out.println("[luceedebug] debugger connection closed");
            }
        }
        catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
            return null;
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
        var c = new Capabilities();
        c.setSupportsConfigurationDoneRequest(true);
        c.setSupportsSingleThreadExecutionRequests(true);
        return CompletableFuture.completedFuture(c);
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

    @Override
    public CompletableFuture<Void> attach(Map<String, Object> args) {
        pathTransforms = tryMungePathTransforms(args.get("pathTransforms"));

        clientProxy_.initialized();

        if (pathTransforms.size() == 0) {
            System.out.println("[luceedebug] attached to frontend, using path transforms <none>");
        }
        else {
            System.out.println("[luceedebug] attached to frontend, using path transforms:");
            for (var transform : pathTransforms) {
                System.out.println(transform.asTraceString());
            }
        }

        return CompletableFuture.completedFuture(null);
    }

    static final Pattern threadNamePrefixAndDigitSuffix = Pattern.compile("^(.+?)(\\d+)$");

    @Override
    public CompletableFuture<ThreadsResponse> threads() {
        var lspThreads = new ArrayList<org.eclipse.lsp4j.debug.Thread>();

        for (var threadRef : luceeVm_.getThreadListing()) {
            try {
                var lspThread = new org.eclipse.lsp4j.debug.Thread();
                lspThread.setId((int)threadRef.uniqueID()); // <<<<----------------@fixme, ObjectCollectedExceptions here
                lspThread.setName(threadRef.name()); // <<<<----------------@fixme, ObjectCollectedExceptions here
                lspThreads.add(lspThread);
            }
            catch (ObjectCollectedException e) {
                // Discard this exception.
                // We really shouldn't be dealing in terms of jdi thread refs here.
                // The luceevm should return a list of names and IDs rather than actual threadrefs.
            }
            catch (Throwable e) {
                e.printStackTrace();
                System.exit(1);
            }
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
        var lspFrames = new ArrayList<org.eclipse.lsp4j.debug.StackFrame>();

        for (var cfFrame : luceeVm_.getStackTrace(args.getThreadId())) {
            final var source = new Source();
            source.setPath(applyPathTransformsCfToIde(cfFrame.getSourceFilePath()));
    
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
        var variables = new ArrayList<Variable>();
        for (var entity : luceeVm_.getVariables(args.getVariablesReference())) {
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
    public CompletableFuture<SetBreakpointsResponse> setBreakpoints(SetBreakpointsArguments args) {
        final var path = new OriginalAndTransformedString(args.getSource().getPath(), applyPathTransformsIdeToCf(args.getSource().getPath()));
        System.out.println("bp for " + path.original + " -> " + path.transformed);
        final int size = args.getBreakpoints().length;
        final int[] lines = new int[size];
        for (int i = 0; i < size; ++i) {
            lines[i] = args.getBreakpoints()[i].getLine();
        }

        var result = new ArrayList<Breakpoint>();
        for (var cfBreakpoint : luceeVm_.bindBreakpoints(path, lines)) {
            result.add(map_cfBreakpoint_to_lsp4jBreakpoint(cfBreakpoint));
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
        // set success false?
		return CompletableFuture.completedFuture(new SetExceptionBreakpointsResponse());
	}

    /**
     * Can we disable the UI for this in the client plugin?
     * 
     * @unsupported
     */
	public CompletableFuture<Void> pause(PauseArguments args) {
        // set success false?
		return CompletableFuture.completedFuture(null);
	}

    @Override
	public CompletableFuture<Void> disconnect(DisconnectArguments args) {
        luceeVm_.clearAllBreakpoints();
        luceeVm_.continueAll();
		return CompletableFuture.completedFuture(null);
	}

    @Override
	public CompletableFuture<ContinueResponse> continue_(ContinueArguments args) {
		luceeVm_.continue_(args.getThreadId());
        return CompletableFuture.completedFuture(new ContinueResponse());
	}

    @Override
	public CompletableFuture<Void> next(NextArguments args) {
		luceeVm_.stepOver(args.getThreadId());
        return CompletableFuture.completedFuture(null);
	}

    @Override
	public CompletableFuture<Void> stepIn(StepInArguments args) {
        luceeVm_.stepIn(args.getThreadId());
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public CompletableFuture<Void> stepOut(StepOutArguments args) {
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
        @Pure
        public String toString() {
          ToStringBuilder b = new ToStringBuilder(this);
          b.add("variablesReference", this.variablesReference);
          return b.toString();
        }

        @Override
        @Pure
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
        @Pure
        public String toString() {
          ToStringBuilder b = new ToStringBuilder(this);
          b.add("content", this.content);
          return b.toString();
        }

        @Override
        @Pure
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
        final var response = new DumpResponse();
        response.setContent(luceeVm_.dump(args.variablesReference));
        return CompletableFuture.completedFuture(response);
	}

    @JsonRequest
	CompletableFuture<DumpResponse> dumpAsJSON(DumpArguments args) {
        final var response = new DumpResponse();
        response.setContent(luceeVm_.dumpAsJSON(args.variablesReference));
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
        @Pure
        public String toString() {
            ToStringBuilder b = new ToStringBuilder(this);
            b.add("canonicalFilenames", this.canonicalFilenames);
            b.add("breakpoints", this.breakpoints);
            b.add("pathTransforms", this.pathTransforms);
            return b.toString();
        }

        @Override
        @Pure
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
        @Pure
        public String toString() {
            ToStringBuilder b = new ToStringBuilder(this);
            b.add("variablesReference", this.variablesReference);
            return b.toString();
        }

        @Override
        @Pure
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
        @Pure
        public String toString() {
            ToStringBuilder b = new ToStringBuilder(this);
            b.add("path", this.path);
            return b.toString();
        }

        @Override
        @Pure
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
        final var response = new GetSourcePathResponse();
        final var serverPath = luceeVm_.getSourcePathForVariablesRef(args.getVariablesReference());

        if (serverPath != null) {
            response.setPath(applyPathTransformsCfToIde(serverPath));
        }
        else {
            response.setPath(null);
        }

        return CompletableFuture.completedFuture(response);
	}
}
