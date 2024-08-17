package luceedebug.coreinject;

class Utils {
    public static <T> T terminate(Throwable e) {
        e.printStackTrace();
        System.exit(1);
        return null;
    }

    public static <T> T unreachable() {
        return unreachable("unreachable");
    }

    public static <T> T unreachable(String s) {
        terminate(new RuntimeException(s));
        return null;
    }
}
