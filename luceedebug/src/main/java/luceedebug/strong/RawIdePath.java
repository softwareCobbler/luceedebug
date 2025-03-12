package luceedebug.strong;

public final class RawIdePath extends StrongT<String> {
    public RawIdePath(String v) {
        super(v);
    }

    @Override
    public String toString() {
        return this.get();
    }
}
