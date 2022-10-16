package asm.use;

public class ThisShouldGetInstrumented {
    public static void doTheThing(Object o) {
        System.out.println("doTheThing(Object o) called");
        doTheOtherThing(o);
    }

    public static void doTheOtherThing(Object o) {
        System.out.println("doTheOtherThing(Object o) called");
    }
}
