package luceedebug;

import java.lang.instrument.*;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;
import java.io.File;

import luceedebug.LuceeTransformer.ClassInjection;
import luceedebug.generated.Constants;

public class Agent {
    /**
     * We require the following invocation
     *
     * -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=jdwpHost:1234
     * -javaagent:/abspath/to/jarfile.jar=jdwpHost=jdwpHost,jdwpPort=1234,debugHost=debugHost,debugPort=5678,jarPath=/abspath/to/jarfile.jar
     * 
     * where jdwpHost and cfHost are `localhost` or `0.0.0.0` or etc.
     *
     * Note we have to repeat the jar path in the javaagent args, it would be nice to not require that.
     *
     */
    static class AgentArgs {
        /**
         * host/port for jdwp connection (i.e. JVM was spawned with libjdwp configured to listen to this)
         * we will connect directly to this when cf spins up, and remain connected for the rest of cf's lifetime
         */
        String jdwpHost;
        int jdwpPort;

        /**
         * host/port for dap connection (i.e. IDE connects to this)
         */
        String debugHost;
        int debugPort;

        /**
         * Path to "this" jar, as in, the Jar that contains this Agent
         * There doesn't seem to be a good way to ask the JVM for "what is the abspath to the jar of this.getClass()",
         * (or we don't know how to ask it...)
         * For the time being, we require the user supply this.
         */
        String jarPath;

        AgentArgs(String argString) {
            boolean gotJdwpHost = false;
            boolean gotJdwpPort = false;
            boolean gotDebugHost = false;
            boolean gotDebugPort = false;
            boolean gotJarPath = false;

            for (var eachArg : argString.split(",")) {
                final var nameAndValue = eachArg.split("=");
                if (nameAndValue.length != 2) {
                    throw new IllegalArgumentException("Invalid agent args string.");
                }

                final var name = nameAndValue[0];
                final var value = nameAndValue[1];

                switch (name.toLowerCase()) {
                    case "jdwphost": {
                        jdwpHost = value;
                        gotJdwpHost = true;
                        break;    
                    }
                    case "cfhost":
                        // fallthrough (cfhost is deprecated in favor of debughost)
                    case "debughost": {
                        debugHost = value;
                        gotDebugHost = true;
                        break;
                    }
                    case "jdwpport": {
                        try {
                            jdwpPort = Integer.parseInt(value);
                            gotJdwpPort = true;
                        }
                        catch (NumberFormatException e) {
                            throw new IllegalArgumentException("Invalid jdwpPort value in agent args string (got '" + value + "' but expected an integer).");
                        }
                        break;
                    }
                    case "cfport":
                        // fallthrough (cfport is deprecated in favor of debugport)
                    case "debugport": {
                        try {
                            debugPort = Integer.parseInt(value);
                            gotDebugPort = true;
                        }
                        catch (NumberFormatException e) {
                            throw new IllegalArgumentException("Invalid debugPort value in agent args string (got '" + value + "' but expected an integer).");
                        }
                        break;
                    }
                    case "jarpath": {
                        jarPath = value;
                        gotJarPath = true;
                        break;
                    }
                }
            }

            {
                var doThrow = false;
                var errMsg = new StringBuilder();
                errMsg.append("Missing agent args:");
                if (!gotJdwpHost) {
                    doThrow = true;
                    errMsg.append(" jdwphost");
                }
                if (!gotDebugHost) {
                    doThrow = true;
                    errMsg.append(" debughost");
                }
                if (!gotJdwpPort) {
                    doThrow = true;
                    errMsg.append(" jdwpport");
                }
                if (!gotDebugPort) {
                    doThrow = true;
                    errMsg.append(" debugport");
                }
                if (!gotJarPath) {
                    doThrow = true;
                    errMsg.append(" jarpath");
                }
                if (doThrow) {
                    throw new IllegalArgumentException(errMsg.toString());
                }
            }
        }
    }

    /**
     * There is probably a way to do this automatically, but we do it manually for now.
     * The injected classes need to be injected in a particular order with respect to class hierarchy.
     * For classes that don't derive from anything, order is irrelevant; but if, within the coreinject package,
     * there are hierarchies, the supertypes need to be loaded first, then the subtype, then the subsubtype, and so on.
     * If a class is added to coreinject and we do not have ordering information for it here, the agent should fail to start
     * with a message regarding which class was missing this information.
     */
    private static class CoreInjectionLinearization {
        private static Map<String, Integer> linearizedCoreInjectClasses() {
            var result = new HashMap<String, Integer>();
    
            result.put("luceedebug.coreinject.LuceeVm$BpLineAndId", 0);
            result.put("luceedebug.coreinject.DebugManager$2", 0);
            result.put("luceedebug.coreinject.DebugManager$CfStepRequest", 0);
            result.put("luceedebug.coreinject.LuceeVm$ReplayableCfBreakpointRequest", 0);
            result.put("luceedebug.coreinject.Utils", 0);
            result.put("luceedebug.coreinject.ValTracker$WeakTaggedObject", 0);
            result.put("luceedebug.coreinject.DebugManager$1", 0);
            result.put("luceedebug.coreinject.ClosureScopeLocalScopeAccessorShim", 0);
            result.put("luceedebug.coreinject.ComponentScopeMarkerTraitShim", 0);
            result.put("luceedebug.coreinject.LuceeVm$SteppingState", 0);
            result.put("luceedebug.coreinject.LuceeVm$KlassMap", 0);
            result.put("luceedebug.coreinject.LuceeVm$JdwpWorker", 0);
            result.put("luceedebug.coreinject.ValTracker$TaggedObject", 0);
            result.put("luceedebug.coreinject.DebugEntity", 0);
            result.put("luceedebug.coreinject.Breakpoint", 0);
            result.put("luceedebug.coreinject.CfValueDebuggerBridge$MarkerTrait", 0);
            result.put("luceedebug.coreinject.ValTracker", 0);
            result.put("luceedebug.coreinject.UnsafeUtils", 0);
            result.put("luceedebug.coreinject.CfValueDebuggerBridge$MarkerTrait$Scope", 0);
            result.put("luceedebug.coreinject.DebugManager$PageContextAndOutputStream", 0);
            result.put("luceedebug.coreinject.LuceeVm$ThreadMap", 0);
            result.put("luceedebug.coreinject.DebugManager", 0);
            result.put("luceedebug.coreinject.LuceeVm$JdwpStaticCallable", 0);
            result.put("luceedebug.coreinject.CfValueDebuggerBridge", 0);
            result.put("luceedebug.coreinject.LuceeVm", 0);
            result.put("luceedebug.coreinject.ValTracker$CleanerRunner", 0);
            result.put("luceedebug.coreinject.ExprEvaluator", 0);

            result.put("luceedebug.coreinject.ExprEvaluator$Evaluator", 0);
            result.put("luceedebug.coreinject.ExprEvaluator$Lucee6Evaluator", 1);
            result.put("luceedebug.coreinject.ExprEvaluator$Lucee5Evaluator", 1);

            result.put("luceedebug.coreinject.frame.DebugFrame", 0);
            result.put("luceedebug.coreinject.frame.Frame", 1);
            result.put("luceedebug.coreinject.frame.Frame$FrameContext", 1);
            result.put("luceedebug.coreinject.frame.Frame$FrameContext$SupplierOrNull", 1);
            result.put("luceedebug.coreinject.frame.DummyFrame", 1);
    
            return result;
        }
    
        public static Comparator<ClassInjection> comparator() {
            final Map<String, Integer> ordering = linearizedCoreInjectClasses();
            return Comparator.comparing(injection -> {
                var v = ordering.get(injection.name);
                if (v == null) {
                    throw new RuntimeException("Missing linearized sort order information for class '" + injection.name + "'");
                }
                else {
                    return v;
                }
            });
        }
    }

    public static void premain(String argString, Instrumentation inst) throws Throwable {
        final var parsedArgs = new AgentArgs(argString);

        if (!new File(parsedArgs.jarPath).exists()) {
            System.err.println("[luceedebug] couldn't find agent/instrumentation jar to add to bootstrap classloader");
            System.err.println("[luceedebug] (target jarpath was '" + parsedArgs.jarPath + "', maybe it was a relative path, rather than absolute?");
            System.exit(1);
        }

        /**
         * Generally useful for debugging, and also has the side-effect of causing the engine
         * to write the full absolute file paths into the generated classfiles, which we need
         * to match up classfiles to IDE sourcefiles.
         */
        System.setProperty("lucee.requesttimeout", "false");

        //
        // This used to be accomplished by instrumenting the Felix constructor (that we expect Lucee to be using).
        // Lucee later added the ability to specify these as envvars or system properties, see:
        //  - https://luceeserver.atlassian.net/browse/LDEV-4193
        //  - https://github.com/lucee/Lucee/commit/ba63a11188f20fac04c6a69529c7cfc55023189e
        //  - lucee tag 5.3.9.194
        // But, we didn't take notice of this, and it didn't hurt to keep going the "add to boot delegation via instrumentation" route.
        //
        // In the changeset from 5.4.0.45 -> 5.4.0.46, lucee's Felix dependency was upgraded to major version 7 from major version 6.
        // In ways that aren't clear, this broke the instrumentation based approach, and osgi classloaders couldn't find anything in `luceedebug.shadowjar.*`.
        // Using the systemprop approach appears to be the solution to the breakage. We should investigate this to understand why the behavior changed.
        //
        // We also retain the instrumentation based approach for users who are on versions before 5.3.9.194.
        // The two approaches appear to coexist without issue.
        //
        // See(lucee): loader/src/main/java/lucee/loader/engine/CFMLEngineFactory.java
        //
        System.setProperty("org.osgi.framework.bootdelegation", "com.sun.jdi,com.sun.jdi.connect,com.sun.jdi.event,com.sun.jdi.request,luceedebug,luceedebug_shadow.*");

        // touch System.out before agent is loaded, otherwise trying to print from within the agent during jvm initialization phase
        // can trigger stackoverflows. And note that System.out.println("") doesn't seem to work, as if printing the empty string
        // early returns, and skips whatever classloading we need to do.
        // TODO: clarify the exact failure case we are attempting to workaround here.
        System.out.println("[luceedebug] version " + Constants.version);

        try (var jarFile = new JarFile(parsedArgs.jarPath)) {
            inst.appendToSystemClassLoaderSearch(jarFile);
            var classInjections = jarFile
                .stream()
                .filter(jarEntry -> !jarEntry.isDirectory() && jarEntry.getName().startsWith("luceedebug/coreinject") && jarEntry.getName().endsWith(".class"))
                .map(jarEntry -> {
                    try (var is = jarFile.getInputStream(jarEntry)) {
                        // foo/bar/baz/Qux.class --> foo.bar.baz.Qux
                        var name = jarEntry.getName().replace(".class", "").replaceAll("/", ".");
                        var bytes = is.readAllBytes();
                        var result = new ClassInjection(name, bytes);
                        return result;
                    }
                    catch (Throwable e) {
                        e.printStackTrace();
                        System.exit(1);
                        return null;
                    }
                })
                .sorted(CoreInjectionLinearization.comparator())
                .toArray(size -> new ClassInjection[size]);

            final var config = new Config(Config.checkIfFileSystemIsCaseSensitive(parsedArgs.jarPath));
            final var transformer = new LuceeTransformer(classInjections, parsedArgs.jdwpHost, parsedArgs.jdwpPort, parsedArgs.debugHost, parsedArgs.debugPort, config);
            inst.addTransformer(transformer);
        }
        catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }

        System.out.println("[luceedebug] agent premain complete");
    }
}
