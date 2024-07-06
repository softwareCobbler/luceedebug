package luceedebug.coreinject;

import lucee.runtime.exp.PageException;

class UnsafeUtils {
    @SuppressWarnings("unchecked")
    static <T> T uncheckedCast(Object e) {
        return (T)e;
    }

    @SuppressWarnings("deprecation")
    public static Object deprecatedScopeGet(lucee.runtime.type.Collection scope, String key) throws PageException {
        // lucee wants us to use Collection's
        // public Object get(Collection.Key key) throws PageException;
        return scope.get(key);
    }
}
