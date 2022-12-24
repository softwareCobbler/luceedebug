package luceedebug.instrumenter;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

public class Felix extends ClassVisitor {

    public Felix(
        int api,
        ClassWriter cw
    ) {
        super(api, cw);
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

        if (name.equals("<init>") && descriptor.equals("(Ljava/util/Map;)V")) {
            return new AdviceAdapter(this.api, mv, access, name, descriptor) {
                @Override
                protected void onMethodEnter() {
                    //
                    // stack is represented after each op
                    //

                    this.loadArg(0);
                    // [Map]

                    this.push("org.osgi.framework.bootdelegation");
                    // [Map, String]
                    
                    this.loadArg(0);
                    // [Map, String, Map]
                    
                    this.push("org.osgi.framework.bootdelegation");
                    // [Map, String, Map, String]
                    
                    this.invokeInterface(Type.getType("Ljava/util/Map;"), Method.getMethod("Object get(Object)"));
                    // [Map, String, Object]

                    this.checkCast(Type.getType("Ljava/lang/String;"));
                    // [Map, String, String]
                    
                    this.push(",com.sun.net.httpserver,com.sun.jdi,com.sun.jdi.connect,com.sun.jdi.event,com.sun.jdi.request,luceedebug,luceedebug_shadow.*");
                    // [Map, String, String, String]
                    
                    this.invokeVirtual(Type.getType("Ljava/lang/String;"), Method.getMethod("String concat(String)"));
                    // [Map, String, String]
                    
                    this.invokeInterface(Type.getType("Ljava/util/Map;"), Method.getMethod("Object put(Object, Object)"));
                    // [Object]
                    
                    this.pop();
                    // []
                }
            };
        }

        return mv;
    }
}
