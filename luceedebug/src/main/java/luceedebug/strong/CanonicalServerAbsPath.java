package luceedebug.strong;

public final class CanonicalServerAbsPath extends StrongT<String> {
    public CanonicalServerAbsPath(String v) {
        super(v);
    }

    @Override
    public String toString() {
        return this.get();
    }
}
