package luceedebug.testutils;

public class Utils {
    public static <T> T unreachable() {
        throw new RuntimeException("unreachable");
    }
}
