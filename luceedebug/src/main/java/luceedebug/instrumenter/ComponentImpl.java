package luceedebug.instrumenter;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.GeneratorAdapter;

public class ComponentImpl extends ClassVisitor {
    public ComponentImpl(int api, ClassWriter cw) {
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
        augmentedInterfaces[interfaces.length] = "luceedebug/coreinject/ComponentScopeMarkerTraitShim";

        super.visit(version, access, name, signature, superName, augmentedInterfaces);
    }

    @Override
    public void visitEnd() {
        final var fieldName = "__luceedebug__pinned_componentScopeMarkerTrait";
        visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_TRANSIENT, fieldName, "Ljava/lang/Object;", null, null);

        final var name = "__luceedebug__pinComponentScopeMarkerTrait";
        final var descriptor = "(Ljava/lang/Object;)V";
        final var mv = visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        final var ga = new GeneratorAdapter(mv, org.objectweb.asm.Opcodes.ACC_PUBLIC, name, descriptor);

        ga.loadThis();
        ga.loadArg(0);
        ga.putField(org.objectweb.asm.Type.getType("Llucee/runtime/ComponentImpl;"), fieldName, org.objectweb.asm.Type.getType("Ljava/lang/Object;"));
        ga.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        ga.endMethod();
    }
}
