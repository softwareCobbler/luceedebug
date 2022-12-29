package luceedebug;

import java.io.File;

public class Config {
    private final boolean fsIsCaseSensitive_;

    Config(boolean fsIsCaseSensitive) {
        this.fsIsCaseSensitive_ = fsIsCaseSensitive;
    }

    private static String invertCase(String path) {
        return path;
    }

    public static boolean checkIfFileSystemIsCaseSensitive(String absPath) {
        if (!(new File(absPath).exists())) {
            throw new RuntimeException("File '" + absPath + "' doesn't exist, so it can't be used to check if the filesystem is case sensitive.");
        }
        return !(new File(invertCase(absPath))).exists();
    }

    public boolean getFsIsCaseSensitive() {
        return fsIsCaseSensitive_;
    }

    public String canonicalizePath(String path) {
        if (fsIsCaseSensitive_) {
            return path;
        }
        else {
            return path.toLowerCase();
        }
    }

    public UiAndCanonicalString canonicalizedPath(String path) {
        return new UiAndCanonicalString(path, canonicalizePath(path));
    }
}
