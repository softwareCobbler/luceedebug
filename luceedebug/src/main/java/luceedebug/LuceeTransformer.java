package luceedebug;

import java.lang.instrument.*;
import java.lang.reflect.Method;

import org.objectweb.asm.*;

import luceedebug.instrumenter.CfmOrCfc;

import java.security.ProtectionDomain;

public class LuceeTransformer implements ClassFileTransformer {
    // hm ... can there be 2 different engines on the same vm, with different loaders? 
    // would that happen alot in a dev environment where you want to hook up a debugger?
    static private ClassLoader luceeCoreLoader = null;
    private long totalInstrumentationTime = 0;

    private final String jdwpHost;
    private final int jdwpPort;
    private final String cfHost;
    private final int cfPort;

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

    public LuceeTransformer(
        ClassInjection[] injections,
        String jdwpHost,
        int jdwpPort,
        String cfHost,
        int cfPort
    ) {
        this.pendingCoreLoaderClassInjections = injections;

        this.jdwpHost = jdwpHost;
        this.jdwpPort = jdwpPort;
        this.cfHost = cfHost;
        this.cfPort = cfPort;
    }

    public byte[] transform(ClassLoader loader,
        String className,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain,
        byte[] classfileBuffer
    ) throws IllegalClassFormatException {
        long rstart = System.nanoTime();
        var classReader = new ClassReader(classfileBuffer);
        String superClass = classReader.getSuperName();
        long rend = System.nanoTime();

        if (className.equals("org/apache/felix/framework/Felix")) {
            return instrumentFelix(classfileBuffer, loader);
        }
        else if (className.equals("lucee/runtime/PageContextImpl")) {
            luceeCoreLoader = loader;

            try {
                Method m = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
                m.setAccessible(true);

                for (var injection : pendingCoreLoaderClassInjections) {
                    // warn: reflection is illegal in 17+, maybe earlier
                    // What, we need to go to JNI to do this?
                    m.invoke(luceeCoreLoader, injection.name, injection.bytes, 0, injection.bytes.length);
                }
                
                pendingCoreLoaderClassInjections = null;
                
                return instrumentPageContextImpl(classfileBuffer);
            }
            catch (Throwable e) {
                e.printStackTrace();
                System.exit(1);
                return null;
            }
        }
        else if (superClass.equals("lucee/runtime/ComponentPageImpl") || superClass.equals("lucee/runtime/PageImpl")) {
            long start = System.nanoTime();
            if (luceeCoreLoader == null) {
                System.out.println("Got class " + className + " before receiving PageContextImpl, debugging will fail.");
                System.exit(1);
            }

            var result = instrumentCfmOrCfc(classfileBuffer, classReader, className);
            long end = System.nanoTime();
            totalInstrumentationTime += (end - start) + (rend - rstart);
            return result;
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
            var instrumenter = new luceedebug.instrumenter.PageContextImpl(Opcodes.ASM9, classWriter, jdwpHost, jdwpPort, cfHost, cfPort);
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
                return luceeCoreLoader;
            }
        };

        try {
            var instrumenter = new CfmOrCfc(Opcodes.ASM9, classWriter, className);
            var classReader = new ClassReader(stepInstrumentedBuffer);

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
}
