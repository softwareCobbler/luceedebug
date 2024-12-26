package luceedebug.strong;

/**
 * Typical use case here is derived classes are final and
 * are simple "strong" wrappers around the underlying type `T`.
 */
public abstract class StrongT<T> {
    private final T v;

    StrongT(T v) {
        this.v = v;
    }

    public T get() {
        return v;
    }

    @Override
    public int hashCode() {
        return v.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        return (other instanceof StrongT<?>) && v.equals(((StrongT<?>)other).v);
    }
}
