package luceedebug;

public class StrongLong {
    public final Long v;
    private StrongLong(Long v) {
        this.v = v;
    }

    @Override
    public int hashCode() {
        return v.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        return (other instanceof StrongLong) && v.equals(((StrongLong)other).v);
    }

    public static class JdwpThreadID extends StrongLong {
        public JdwpThreadID(Long v) {
            super(v);
        }

        public static JdwpThreadID of(com.sun.jdi.ThreadReference v) {
            return new JdwpThreadID(v.uniqueID());
        }
    }
}
