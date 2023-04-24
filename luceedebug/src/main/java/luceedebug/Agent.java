package luceedebug;

import java.lang.instrument.*;

import java.util.jar.JarFile;
import java.io.File;

import luceedebug.LuceeTransformer.ClassInjection;

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
     * -javaagent:/abspath/to/jarfile.jar=withSemiMixedMode,jdwpHost=jdwpHost,jdwpPort=1234,debugHost=debugHost,debugPort=5678,javaDebugHost=X,javaDebugPort=X,jdwpProxyHost=X,jdwpProxyPort=X,jarPath=/abspath/to/jarfile.jar
     *
     */
    static class AgentArgs {
        /**
         * host/port for jdwp connection (i.e. JVM was spawned with libjdwp configured to listen to this)
         * Luceedebug will connect directly to this when cf spins up, and remain connected for the rest of cf's lifetime
         */
        String jdwpHost;
        int jdwpPort;

        /**
         * host/port for lucee dap connection (i.e. IDE connects to this)
         */
        String luceeDebugHost;
        int luceeDebugPort;

        /**
         * if we're configured to accept 2 JDWP connections, to support (semi-)mixed-mode debugging,
         * we need a port to listen on to accept the second connection.
         */
        boolean withSemiMixedMode = false;
        String javaDebugHost;
        int javaDebugPort;

        /**
         * Path to "this" jar, as in, the Jar that contains this Agent
         * There doesn't seem to be a good way to ask the JVM for "what is the abspath to the jar of this.getClass()",
         * (or we don't know how to ask it...)
         * For the time being, we require the user supply this.
         */
        String jarPath;

        AgentArgs(String in_argString) {
            boolean gotJdwpHost = false;
            boolean gotJdwpPort = false;
            boolean gotLuceeDebugHost = false;
            boolean gotLuceeDebugPort = false;
            boolean gotJarPath = false;
            boolean gotJavaDebugHost = false;
            boolean gotJavaDebugPort = false;

            String argString = in_argString;
            {
                final var semiMixedModeArg1 = "withSemiMixedMode,";
                if (argString.startsWith(semiMixedModeArg1)) {
                    argString = argString.substring(semiMixedModeArg1.length());
                    withSemiMixedMode = true;
                }
            }

            for (var eachArg : argString.split(",")) {
                final var nameAndValue = eachArg.split("=");
                if (nameAndValue.length != 2) {
                    throw new IllegalArgumentException("Invalid agent args string `" + argString + "`.");
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
                        luceeDebugHost = value;
                        gotLuceeDebugHost = true;
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
                        luceeDebugPort = parseIntOrFailWithMsg(value, "Invalid debugPort value in agent args string (got '" + value + "' but expected an integer).");
                        gotLuceeDebugPort = true;
                        break;
                    }
                    case "jarpath": {
                        jarPath = value;
                        gotJarPath = true;
                        break;
                    }
                    case "javadebughost": {
                        javaDebugHost = value;
                        gotJavaDebugHost = true;
                        break;
                    }
                    case "javadebugport": {
                        javaDebugPort = parseIntOrFailWithMsg(value, "Invalid javaDebugPort value in agent args string (got '" + value + "' but expected an integer).");
                        gotJavaDebugPort = true;
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
                if (!gotLuceeDebugHost) {
                    doThrow = true;
                    errMsg.append(" debughost");
                }
                if (!gotJdwpPort) {
                    doThrow = true;
                    errMsg.append(" jdwpport");
                }
                if (!gotLuceeDebugPort) {
                    doThrow = true;
                    errMsg.append(" debugport");
                }
                if (!gotJarPath) {
                    doThrow = true;
                    errMsg.append(" jarpath");
                }
                if (withSemiMixedMode) {
                    if (!gotJavaDebugHost) {
                        doThrow = true;
                        errMsg.append(" javaDebugHost (because 'withJdwpProxy' was specified)");
                    }
                    if (!gotJavaDebugPort) {
                        doThrow = true;
                        errMsg.append(" javaDebugPort (because 'withJdwpProxy' was specified)");
                    }
                }
                if (doThrow) {
                    System.err.println("[luceedebug] bad agent arg string `" + in_argString + "`");
                    throw new IllegalArgumentException(errMsg.toString());
                }
            }
        }

        private int parseIntOrFailWithMsg(String value, String msg) {
            try {
                return Integer.parseInt(value);
            }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException(msg);
            }
        }
    }

    public static void premain(String argString, Instrumentation inst) throws Throwable {
        final var parsedArgs = new AgentArgs(argString);

        String effectiveJdwpHost = parsedArgs.jdwpHost;
        int effectiveJdwpPort = parsedArgs.jdwpPort;

        if (parsedArgs.withSemiMixedMode) {
            var proxy = new dwr.LuceedebugJdwpProxy(
                /*actualJvmJdwpHost*/ parsedArgs.jdwpHost,
                /*actualJvmJdwpPort*/ parsedArgs.jdwpPort,
                /*javaDebugHost*/ parsedArgs.javaDebugHost,
                /*javaDebugPort*/ parsedArgs.javaDebugPort
            );
            effectiveJdwpHost = proxy.getInternalLuceedebugHost();
            effectiveJdwpPort = proxy.getInternalLuceedebugPort();
        }

        if (!new File(parsedArgs.jarPath).exists()) {
            System.err.println("[luceedebug] couldn't find agent/instrumentation jar to add to bootstrap classloader");
            System.err.println("[luceedebug] (target jarpath was '" + parsedArgs.jarPath + "', maybe it was a relative path, rather than absolute?");
            System.exit(1);
        }

        System.setProperty("lucee.requesttimeout", "false");

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
                .sorted((l,r) -> {
                    // we don't need to sort, if we have no dependencies from within coreinject into coreinject
                    // (i.e. there is class/interface in coreinject.* that extends/implements another class/interface in coreinject.*)
                    // otherwise, we'd have to inject parents first, then children, then grandchildren, etc.
                    return 0;
                })
                .toArray(size -> new ClassInjection[size]);

            final var config = new Config(Config.checkIfFileSystemIsCaseSensitive(parsedArgs.jarPath));
            System.out.println("[luceedebug] fs is case sensitive: " + config.getFsIsCaseSensitive());
            final var transformer = new LuceeTransformer(
                classInjections,
                effectiveJdwpHost,
                effectiveJdwpPort,
                parsedArgs.luceeDebugHost,
                parsedArgs.luceeDebugPort,
                config
            );
            inst.addTransformer(transformer);
            transformer.makeSystemOutPrintlnSafeForUseInTransformer();
        }
        catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }

        System.out.println("[luceedebug] agent premain complete");
    }
}
