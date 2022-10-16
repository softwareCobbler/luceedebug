package luceedebug.test;

public class MockCfFile {
    public static void main(String[] args) {
        // we want to see that
        // - this file gets it's source info updated in the its corresponding classfile,
        //   such that it looks like a class file that originated from a cf source file
        // - we can set breakpoints in it
    }

    public static String bar() {
        int i = 0;
        i++;
        i++;
        i++;
        return Integer.toString(i);
    }
}
