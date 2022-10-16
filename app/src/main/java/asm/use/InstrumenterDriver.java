package asm.use;
import luceedebug.*;

public class InstrumenterDriver {
    public static void main(String[] args) throws Throwable {
        System.out.println("Instrumenter main");
        ThisShouldGetInstrumented.doTheThing(Thread.currentThread());
        System.out.println("Instrumented int: " + ThisIsCalledByInstrumentedCode.thread_to_pageContext_map.get(Thread.currentThread()));
    }
}
