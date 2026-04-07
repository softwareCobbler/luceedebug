package luceedebug;

import java.io.File;

public class Config {
    private final boolean fsIsCaseSensitive_;
    // we probably never want to step into this (the a=b in `function foo(a=b) { ... }` )
    // but for now it's configurable
    private boolean stepIntoUdfDefaultValueInitFrames_ = false;

    /**
     * Static cache of filesystem case sensitivity.
     * Set once at startup when Config is instantiated.
     * Used by canonicalizeFileName() to skip lowercase on case-sensitive filesystems.
     */
    private static volatile boolean staticFsIsCaseSensitive = false;

    /**
     * Base path prefix for shortening paths in log output.
     * Set from pathTransforms when DAP client attaches.
     */
    private static volatile String basePath = null;

    public Config(boolean fsIsCaseSensitive) {
        this.fsIsCaseSensitive_ = fsIsCaseSensitive;
        // Cache for static access
        staticFsIsCaseSensitive = fsIsCaseSensitive;
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

    public static String canonicalizeFileName(String s) {
        // Normalize slashes (always needed)
        String normalized = s.replaceAll("[\\\\/]+", "/");
        // Only lowercase on case-insensitive filesystems (Windows)
        return staticFsIsCaseSensitive ? normalized : normalized.toLowerCase();
    }

    /**
     * Set the base path for shortening paths in log output.
     */
    public static void setBasePath(String path) {
        basePath = path != null ? canonicalizeFileName(path) : null;
    }

    /**
     * Shorten a path for display by removing the base path prefix.
     * Returns the relative path (with leading /) if it starts with basePath, otherwise the full path.
     */
    public static String shortenPath(String path) {
        if (basePath == null || path == null) {
            return path;
        }
        String canon = canonicalizeFileName(path);
        if (canon.startsWith(basePath)) {
            String relative = canon.substring(basePath.length());
            // Ensure leading slash
            if (!relative.startsWith("/")) {
                relative = "/" + relative;
            }
            return relative;
        }
        return path;
    }

}
