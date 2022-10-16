package luceedebug.instrumenter;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

public class PageContextImpl extends ClassVisitor {

    final String jdwpHost;
    final int jdwpPort;
    final String cfHost;
    final int cfPort;

    public PageContextImpl(
        int api,
        ClassWriter cw,
        String jdwpHost,
        int jdwpPort,
        String cfHost,
        int cfPort
    ) {
        super(api, cw);
        this.jdwpHost = jdwpHost;
        this.jdwpPort = jdwpPort;
        this.cfHost = cfHost;
        this.cfPort = cfPort;
    }

    @Override
    public MethodVisitor visitMethod(
        final int access,
        final String name,
        final String descriptor,
        final String signature,
        final String[] exceptions
    ) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

        if (name.equals("<clinit>")) {
            return new AdviceAdapter(this.api, mv, access, name, descriptor) {
                @Override
                protected void onMethodEnter() {
                    this.push(jdwpHost);
                    this.push(jdwpPort);
                    this.push(cfHost);
                    this.push(cfPort);
                    this.invokeStatic(Type.getType("Lluceedebug/coreinject/DebugManager;"), Method.getMethod("void spawnWorker(java.lang.String, int, java.lang.String, int)"));
                }
            };
        }
        else {
            return mv;
        }
    }
}
