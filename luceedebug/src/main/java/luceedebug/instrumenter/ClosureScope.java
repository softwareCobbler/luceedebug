package luceedebug.instrumenter;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.GeneratorAdapter;

/**
 * extend lucee.runtime.type.scope.ClosureScope to implement ClosureScopeLocalScopeAccessorShim
 */

public class ClosureScope extends ClassVisitor {
    public ClosureScope(int api, ClassWriter cw) {
        super(api, cw);
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces
    ) {
        final var augmentedInterfaces = new String[interfaces.length + 1];
        for (int i = 0; i < interfaces.length; i++) {
            augmentedInterfaces[i] = interfaces[i];
        }
        augmentedInterfaces[interfaces.length] = "luceedebug/coreinject/ClosureScopeLocalScopeAccessorShim";

        super.visit(version, access, name, signature, superName, augmentedInterfaces);
    }

    @Override
    public void visitEnd() {
        final var name = "getLocalScope";
        final var descriptor = "()Llucee/runtime/type/scope/Scope;";
        final var mv = visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        final var ga = new GeneratorAdapter(mv, org.objectweb.asm.Opcodes.ACC_PUBLIC, name, descriptor);
        ga.loadThis();
        ga.getField(org.objectweb.asm.Type.getType("Llucee/runtime/type/scope/ClosureScope;"), "local", org.objectweb.asm.Type.getType("Llucee/runtime/type/scope/Local;"));
        ga.returnValue();
        ga.endMethod();
    }
}
