package luceedebug;

public class StrongInt {
    public final Integer v;
    private StrongInt(Integer v) {
        this.v = v;
    }

    @Override
    public int hashCode() {
        return v.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        return (other instanceof StrongInt) && v.equals(((StrongInt)other).v);
    }

    public static class DapBreakpointID extends StrongInt {
        public DapBreakpointID(Integer v) {
            super(v);
        }
    }
}
