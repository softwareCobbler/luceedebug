/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package asm.use;

public class WillHaveSourceFileNameTransformed {
    public static void main(String[] args) {
        System.out.println("This is a stub java file, which should have had it's class file's source file set to .cfc after build.");
    }

    public int foo() {
        int i = 0;
        int j = 1;
        int k = 2;
        System.out.println(i);
        System.out.println(j);
        System.out.println(k);
        return i + j + k;
    }

    public String bar() {
        return "bar -> foo -> " + foo();
    }
}
