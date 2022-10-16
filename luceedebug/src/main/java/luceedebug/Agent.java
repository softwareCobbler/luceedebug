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
     * -javaagent:/abspath/to/jarfile.jar=jdwpHost=jdwpHost,jdwpPort=1234,cfHost=cfHost,cfPort=5678,jarPath=/abspath/to/jarfile.jar
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
        String cfHost;
        int cfPort;

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
            boolean gotCfHost = false;
            boolean gotCfPort = false;
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
                    case "cfhost": {
                        cfHost = value;
                        gotCfHost = true;
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
                    case "cfport": {
                        try {
                            cfPort = Integer.parseInt(value);
                            gotCfPort = true;
                        }
                        catch (NumberFormatException e) {
                            throw new IllegalArgumentException("Invalid cfPort value in agent args string (got '" + value + "' but expected an integer).");
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
                if (!gotCfHost) {
                    doThrow = true;
                    errMsg.append(" cfhost");
                }
                if (!gotJdwpPort) {
                    doThrow = true;
                    errMsg.append(" jdwpport");
                }
                if (!gotCfPort) {
                    doThrow = true;
                    errMsg.append(" cfport");
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

    public static void premain(String argString, Instrumentation inst) throws Throwable {
        final var parsedArgs = new AgentArgs(argString);

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

            inst.addTransformer(new LuceeTransformer(classInjections, parsedArgs.jdwpHost, parsedArgs.jdwpPort, parsedArgs.cfHost, parsedArgs.cfPort));
        }
        catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }

        System.out.println("[luceedebug] agent premain complete");
    }
}
