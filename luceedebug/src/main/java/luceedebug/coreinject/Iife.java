package luceedebug.coreinject;

class Iife {
    @FunctionalInterface
    public interface Supplier2<T> {
        T get() throws Throwable;
    }
    
    @SuppressWarnings("unchecked")
    static public <T extends Throwable, R> R rethrowUnchecked(Object e) throws T {
        throw (T) e;
    }

    static public <T> T iife(Supplier2<T> f) {
        try {
            return f.get();
        }
        catch (Throwable e) {
            return rethrowUnchecked(e);
        }
    }
}
