package luceedebug;

/**
 * This smooths out classloading issues with respect isolated OSGi classloaders,
 * similar in spirit to the difference between lucee's "loader" and "core" modules
 * (this would be a "loader" thing, externally visible, providing interface defs
 * to core things).
 */
public class GlobalIDebugManagerHolder {
    // hm ... can there be 2 different engines on the same vm, with different loaders? 
    // would that happen alot in a dev environment where you want to hook up a debugger?
    public static ClassLoader luceeCoreLoader;
    public static IDebugManager debugManager;
}