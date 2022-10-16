package luceedebug.test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Paths;

public class Utils {
    static byte[] readSource(String relPathToSource) throws Throwable {
        String absPathToSource = Paths.get(relPathToSource).toAbsolutePath().toString();
        File source = new File(absPathToSource);
        byte[] result = new byte[(int)source.length()];
        FileInputStream fis = new FileInputStream(source);
        fis.read(result);
        fis.close();
        return result;
    }

    static void writeTarget(byte[] bytes, String relPathToTarget) throws Throwable {
        String absPathToTarget = Paths.get(relPathToTarget).toAbsolutePath().toString();
        File target = new File(absPathToTarget);

        if (target.exists()) {
            target.delete();
            target.createNewFile();
        }

        FileOutputStream fos = new FileOutputStream(target);
        fos.write(bytes);
        fos.close();
    }
}
