/**
 * read a particular target .class file, and transform it to have an updated debug source attribute, 
 * so it looks like a cf file
 */
package luceedebug.test;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

public class MakeMockCfFile {
    static String relPathToSource = "build/classes/java/main/luceedebug/test/MockCfFile.class";
    static String relPathToTarget = "build/classes/java/main/luceedebug/test/MockCfFile.class"; // overwrite the original
    static String mockSourcePath = "/some/abs/project/path/Foo.cfc";

    public static void main(String[] args) throws Throwable {
        byte[] source = Utils.readSource(relPathToSource);
        
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public void visitSource(String source, String debug) {
                super.visitSource(mockSourcePath, null);
            }
        };

        ClassReader reader = new ClassReader(source);
        
        reader.accept(visitor, 0);

        byte[] transformed = cw.toByteArray();

        Utils.writeTarget(transformed, relPathToTarget);
    }
}
