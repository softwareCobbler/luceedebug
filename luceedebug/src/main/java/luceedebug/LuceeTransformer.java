package luceedebug;

import java.lang.instrument.*;
import java.lang.reflect.Method;

import org.objectweb.asm.*;

import luceedebug.instrumenter.CfmOrCfc;

import java.security.ProtectionDomain;
import java.util.ArrayList;

public class LuceeTransformer implements ClassFileTransformer {
    private final String jdwpHost;
    private final int jdwpPort;
    private final String debugHost;
    private final int debugPort;
    private final Config config;

    static public class ClassInjection {
        final String name;
        final byte[] bytes;
        ClassInjection(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }

    /**
     * if non-null, we are awaiting the initial class load of PageContextImpl
     * When that happens, these classes will be injected into that class loader.
     * Then, this should be set to null, since we don't need to hold onto them locally.
     */
    private ClassInjection[] pendingCoreLoaderClassInjections;

    /**
     * this print stuff is debug related;
     * If you want to println at some arbitrary time very soon after initializing the transformer and registering it with the JVM,
     * it's possible that the classes responsible for System.out.println are being loaded when _we_ say System.out.println,
     * which results in cryptic ClassCircularityErrors and eventually assertion failures from JVM native code.
     */
    private boolean systemOutPrintlnLoaded = false;
    private ArrayList<String> pendingPrintln = new ArrayList<>();
    private void println(String s) {
        if (!systemOutPrintlnLoaded) {
            pendingPrintln.add(s);
        }
        else {
            System.out.println(s);
        }
    }
    public void makeSystemOutPrintlnSafeForUseInTransformer() {
        System.out.print("");
        systemOutPrintlnLoaded = true;
        for (var s : pendingPrintln) {
            println(s);
        }
    }

    public LuceeTransformer(
        ClassInjection[] injections,
        String jdwpHost,
        int jdwpPort,
        String debugHost,
        int debugPort,
        Config config
    ) {
        this.pendingCoreLoaderClassInjections = injections;

        this.jdwpHost = jdwpHost;
        this.jdwpPort = jdwpPort;
        this.debugHost = debugHost;
        this.debugPort = debugPort;
        this.config = config;
    }

    public byte[] transform(ClassLoader loader,
        String className,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain,
        byte[] classfileBuffer
    ) throws IllegalClassFormatException {
        var classReader = new ClassReader(classfileBuffer);
        String superClass = classReader.getSuperName();

        if (className.equals("org/apache/felix/framework/Felix")) {
            return instrumentFelix(classfileBuffer, loader);
        }
        else if (className.equals("lucee/runtime/PageContextImpl")) {
            GlobalIDebugManagerHolder.luceeCoreLoader = loader;

            try {
                Method m = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
                m.setAccessible(true);

                for (var injection : pendingCoreLoaderClassInjections) {
                    // warn: reflection ... when does that become unsupported?
                    m.invoke(GlobalIDebugManagerHolder.luceeCoreLoader, injection.name, injection.bytes, 0, injection.bytes.length);
                }
                
                pendingCoreLoaderClassInjections = null;

                try {
                    final var klass = GlobalIDebugManagerHolder.luceeCoreLoader.loadClass("luceedebug.coreinject.DebugManager");
                    GlobalIDebugManagerHolder.debugManager = (IDebugManager)klass.getConstructor().newInstance();

                    System.out.println("[luceedebug] Loaded " + GlobalIDebugManagerHolder.debugManager + " with ClassLoader '" + GlobalIDebugManagerHolder.debugManager.getClass().getClassLoader() + "'");
                    GlobalIDebugManagerHolder.debugManager.spawnWorker(config, jdwpHost, jdwpPort, debugHost, debugPort);
                }
                catch (Throwable e) {
                    e.printStackTrace();
                    System.exit(1);
                }
                
                return classfileBuffer;
            }
            catch (Throwable e) {
                e.printStackTrace();
                System.exit(1);
                return null;
            }
        }
        else if (superClass.equals("lucee/runtime/ComponentPageImpl") || superClass.equals("lucee/runtime/PageImpl")) {
            System.out.println("[luceedebug] Instrumenting " + className);
            if (GlobalIDebugManagerHolder.luceeCoreLoader == null) {
                System.out.println("Got class " + className + " before receiving PageContextImpl, debugging will fail.");
                System.exit(1);
            }

            return instrumentCfmOrCfc(classfileBuffer, classReader, className);
        }
        else {
            return classfileBuffer;
        }
    }

    private byte[] instrumentFelix(final byte[] classfileBuffer, ClassLoader loader) {
        var classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader;
            }
        };

        try {
            var instrumenter = new luceedebug.instrumenter.Felix(Opcodes.ASM9, classWriter);
            var classReader = new ClassReader(classfileBuffer);

            classReader.accept(instrumenter, ClassReader.EXPAND_FRAMES);

            return classWriter.toByteArray();
        }
        catch (Throwable e) {
            System.err.println("[luceedebug] exception during attempted classfile rewrite");
            System.err.println(e.getMessage());
            e.printStackTrace();
            System.exit(1);
            return null;
        }
    }

    private byte[] instrumentPageContextImpl(final byte[] classfileBuffer) {
        // Weird problems if we try to compute frames ... tries to lookup PageContextImpl but then it's not yet available in the classloader?
        // Mostly meaning, don't do things in PageContextImpl that change frame sizes
        var classWriter = new ClassWriter(/*ClassWriter.COMPUTE_FRAMES |*/ ClassWriter.COMPUTE_MAXS);

        try {
            var instrumenter = new luceedebug.instrumenter.PageContextImpl(Opcodes.ASM9, classWriter, jdwpHost, jdwpPort, debugHost, debugPort);
            var classReader = new ClassReader(classfileBuffer);

            classReader.accept(instrumenter, ClassReader.EXPAND_FRAMES);

            return classWriter.toByteArray();
        }
        catch (Throwable e) {
            System.err.println("[luceedebug] exception during attempted classfile rewrite");
            System.err.println(e.getMessage());
            e.printStackTrace();
            System.exit(1);
            return null;
        }
    }
    
    private byte[] instrumentCfmOrCfc(final byte[] classfileBuffer, ClassReader reader, String className) {
        byte[] stepInstrumentedBuffer = classfileBuffer;
        var classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected ClassLoader getClassLoader() {
                return GlobalIDebugManagerHolder.luceeCoreLoader;
            }
        };

        try {
            var instrumenter = new CfmOrCfc(Opcodes.ASM9, classWriter, className);
            var classReader = new ClassReader(stepInstrumentedBuffer);

            classReader.accept(instrumenter, ClassReader.EXPAND_FRAMES);

            return classWriter.toByteArray();
        }
        catch (MethodTooLargeException e) {
            String baseName = e.getMethodName();
            boolean targetMethodWasBeingInstrumented = false;

            if (baseName.startsWith("__luceedebug__")) {
                baseName = baseName.replaceFirst("__luceedebug__", "");
                targetMethodWasBeingInstrumented = true;
            }

            if (targetMethodWasBeingInstrumented) {
                System.err.println("[luceedebug] Method '" + baseName + "' in class '" + className + "' became too large after instrumentation (size="  + e.getCodeSize() + "). luceedebug won't be able to hit breakpoints in, or expose frame information for, this file.");
            }
            else {
                // this shouldn't happen, we really should only get MethodTooLargeExceptions for code we were instrumenting
                System.err.println("[luceedebug] Method " + baseName + " in class " + className + " was too large to for org.objectweb.asm to reemit.");
            }

            return classfileBuffer;
        }
        catch (Throwable e) {
            System.err.println("[luceedebug] exception during attempted classfile rewrite");
            System.err.println(e.getMessage());
            e.printStackTrace();
            System.exit(1);
            return null;
        }
    }
}
