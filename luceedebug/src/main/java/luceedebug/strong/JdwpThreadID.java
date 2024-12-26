package luceedebug.strong;

public final class JdwpThreadID extends StrongT<Long> {
    public JdwpThreadID(Long v) {
        super(v);
    }

    public static JdwpThreadID of(com.sun.jdi.ThreadReference v) {
        return new JdwpThreadID(v.uniqueID());
    }
}
