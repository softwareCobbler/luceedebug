/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package asm.use;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.nio.file.Paths;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.TraceClassVisitor;

public class App {
    static String relPathToSource = "build/classes/java/main/asm/use/WillHaveSourceFileNameTransformed.class";
    static String relPathToTarget = "build/classes/java/main/asm/use/MockCfFile.class";

    public String getGreeting() {
        System.out.println(ClassReader.class);
        return "Hello World! (oye) lel";
    }

    static byte[] readSource() throws Throwable {
        String absPathToSource = Paths.get(relPathToSource).toAbsolutePath().toString();
        File source = new File(absPathToSource);
        byte[] result = new byte[(int)source.length()];
        FileInputStream fis = new FileInputStream(source);
        int bs = fis.read(result);
        System.out.println("Read " + bs + " bytes");
        fis.close();
        return result;
    }

    static void writeTarget(byte[] bytes) throws Throwable {
        String absPathToTarget = Paths.get(relPathToTarget).toAbsolutePath().toString();
        File target = new File(absPathToTarget);
        System.out.println(target);

        if (target.exists()) {
            target.delete();
            target.createNewFile();
        }

        FileOutputStream fos = new FileOutputStream(target);
        fos.write(bytes);
        fos.close();
    }

    public static void main(String[] args) throws Throwable {
        byte[] source = readSource();
        
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public void visitSource(String source, String debug) {
                super.visitSource("/some/abs/project/path/Foo.cfc", null);
            }
        };

        ClassReader reader = new ClassReader(source);
        
        reader.accept(visitor, 0);

        byte[] transformed = cw.toByteArray();

        writeTarget(transformed);

        System.out.println("OK?");
    }
}