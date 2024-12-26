package luceedebug;

public class StrongString {
    public final String v;
    private StrongString(String v) {
        this.v = v;
    }

    @Override
    public int hashCode() {
        return v.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        return (other instanceof StrongString) && v.equals(((StrongString)other).v);
    }

    public static class RawIdePath extends StrongString {
        public RawIdePath(String v) {
            super(v);
        }
    }

    public static class CanonicalServerAbsPath extends StrongString {
        public CanonicalServerAbsPath(String v) {
            super(v);
        }
    }
}
