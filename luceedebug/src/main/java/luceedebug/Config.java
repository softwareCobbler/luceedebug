package luceedebug;

import java.io.File;

public class Config {
    private final boolean fsIsCaseSensitive_;
    private boolean stepIntoUdfDefaultValueInitFrames_ = true;

    Config(boolean fsIsCaseSensitive) {
        this.fsIsCaseSensitive_ = fsIsCaseSensitive;
    }

    public boolean getStepIntoUdfDefaultValueInitFrames() {
        return this.stepIntoUdfDefaultValueInitFrames_;
    }
    public void setStepIntoUdfDefaultValueInitFrames(boolean v) {
        this.stepIntoUdfDefaultValueInitFrames_ = v;
    }

    private static String invertCase(String path) {
        int offset = 0;
        int strLen = path.length();
        final var builder = new StringBuilder();
        while (offset < strLen) {
            int c = path.codePointAt(offset);
            if (Character.isUpperCase(c)) {
                builder.append(Character.toString(Character.toLowerCase(c)));
            }
            else if (Character.isLowerCase(c)) {
                builder.append(Character.toString(Character.toUpperCase(c)));
            }
            else {
                builder.append(Character.toString(c));
            }
            offset += Character.charCount(c);
        }
        return builder.toString();
    }

    public static boolean checkIfFileSystemIsCaseSensitive(String absPath) {
        if (!(new File(absPath)).exists()) {
            throw new IllegalArgumentException("File '" + absPath + "' doesn't exist, so it cannot be used to check for file system case sensitivity.");
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

    public OriginalAndTransformedString canonicalizedPath(String path) {
        return new OriginalAndTransformedString(path, canonicalizePath(path));
    }
}
