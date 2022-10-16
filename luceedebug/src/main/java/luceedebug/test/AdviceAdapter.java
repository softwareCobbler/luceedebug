package luceedebug.test;

import org.objectweb.asm.*;

import luceedebug.instrumenter.CfmOrCfc;

public class AdviceAdapter {
    static String relPathToSource = "build/classes/java/main/luceedebug/test/AdviceAdapterReceiver.class";
    static String relPathToTarget = "build/classes/java/main/luceedebug/test/AdviceAdapterReceiver_xform.class";

    public static void main(String[] args) throws Throwable {
        byte[] b = Utils.readSource(relPathToSource);
        byte[] xform = instrumentCfcOrCfm(b, new ClassReader(b));
        Utils.writeTarget(xform, relPathToTarget);
    }

    static byte[] instrumentCfcOrCfm(byte[] classfileBuffer, ClassReader reader) throws Throwable {
        var classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

        var instrumenter = new CfmOrCfc(Opcodes.ASM9, classWriter, "<test>");
        var classReader = new ClassReader(classfileBuffer);

        classReader.accept(instrumenter, ClassReader.EXPAND_FRAMES);

        byte[] result = classWriter.toByteArray();

        return result;
    }
}
