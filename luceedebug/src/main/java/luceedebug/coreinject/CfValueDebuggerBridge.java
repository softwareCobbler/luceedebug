package luceedebug.coreinject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import lucee.runtime.Component;
import lucee.runtime.type.Array;
import luceedebug.ICfValueDebuggerBridge;
import luceedebug.IDebugEntity;
import luceedebug.coreinject.frame.Frame;

public class CfValueDebuggerBridge implements ICfValueDebuggerBridge {
    // Pin some ephemeral evaluated things so they don't get GC'd immediately.
    // It would be better to pin them to a "session" or something with a meaningful lifetime,
    // rather than hope they live long enough in this cache to be useful.
    // Most objects do not require being pinned here -- objects that require pinning are those we synthetically create
    // while generating debug info, like when we wrap a CFC in a MarkerTrait.Scope, or create an array out of a Query object.
    private static final Cache<Integer, Object> pinnedObjects = CacheBuilder
        .newBuilder()
        .maximumSize(50)
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .build();
    public static void pin(Object obj) {
        pinnedObjects.put(System.identityHashCode(obj), obj);
    }

    private final Frame frame;
    private final ValTracker valTracker;
    public final Object obj;
    public final long id;

    public CfValueDebuggerBridge(Frame frame, Object obj) {
        this.frame = Objects.requireNonNull(frame);
        this.valTracker = frame.valTracker;
        this.obj = Objects.requireNonNull(obj);
        this.id = frame.valTracker.idempotentRegisterObject(obj).id;
    }

    /**
     * Constructor for use with native Lucee7 debugger frames where we don't have a Frame object.
     * The valTracker is stored directly since we don't have a Frame.
     */
    public CfValueDebuggerBridge(ValTracker valTracker, Object obj) {
        this.frame = null; // Not available for native frames
        this.valTracker = Objects.requireNonNull(valTracker);
        this.obj = Objects.requireNonNull(obj);
        this.id = valTracker.idempotentRegisterObject(obj).id;
    }

    public long getID() {
        return id;
    }

    public static class MarkerTrait {
        public static class Scope {
            public final Map<?,?> scopelike;
            public Scope(Map<?,?> scopelike) {
                this.scopelike = scopelike;
            }
        }
    }

    /**
     * @maybeNull_which --> null means "any type"
     */
    public static IDebugEntity[] getAsDebugEntity(Frame frame, Object obj, IDebugEntity.DebugEntityType maybeNull_which) {
        return getAsDebugEntity(frame.valTracker, obj, maybeNull_which, null);
    }

    public static IDebugEntity[] getAsDebugEntity(ValTracker valTracker, Object obj, IDebugEntity.DebugEntityType maybeNull_which) {
        return getAsDebugEntity(valTracker, obj, maybeNull_which, null);
    }

    /**
     * Get debug entities for an object's children.
     * @param valTracker The value tracker
     * @param obj The parent object to expand
     * @param maybeNull_which Filter for named/indexed variables, or null for all
     * @param parentPath The variable path of the parent (e.g., "local.foo"), or null if not tracked
     */
    public static IDebugEntity[] getAsDebugEntity(ValTracker valTracker, Object obj, IDebugEntity.DebugEntityType maybeNull_which, String parentPath) {
        return getAsDebugEntity(valTracker, obj, maybeNull_which, parentPath, null);
    }

    /**
     * Get debug entities for an object's children.
     * @param valTracker The value tracker
     * @param obj The parent object to expand
     * @param maybeNull_which Filter for named/indexed variables, or null for all
     * @param parentPath The variable path of the parent (e.g., "local.foo"), or null if not tracked
     * @param frameId The frame ID for setVariable support, or null if not tracked
     */
    public static IDebugEntity[] getAsDebugEntity(ValTracker valTracker, Object obj, IDebugEntity.DebugEntityType maybeNull_which, String parentPath, Long frameId) {
        final boolean namedOK = maybeNull_which == null || maybeNull_which == IDebugEntity.DebugEntityType.NAMED;
        final boolean indexedOK = maybeNull_which == null || maybeNull_which == IDebugEntity.DebugEntityType.INDEXED;

        if (obj instanceof MarkerTrait.Scope && namedOK) {
            @SuppressWarnings("unchecked")
            var m = (Map<String, Object>)(((MarkerTrait.Scope)obj).scopelike);
            return getAsMaplike(valTracker, m, parentPath, frameId);
        }
        else if (obj instanceof Map && namedOK) {
            if (obj instanceof Component) {
                return new IDebugEntity[] {
                    maybeNull_asValue(valTracker, "this", obj, true, true, parentPath, frameId),
                    maybeNull_asValue(valTracker, "variables", ((Component)obj).getComponentScope(), parentPath, frameId),
                    maybeNull_asValue(valTracker, "static", ((Component)obj).staticScope(), parentPath, frameId)
                };
            }
            else {
                @SuppressWarnings("unchecked")
                var m = (Map<String, Object>)obj;
                return getAsMaplike(valTracker, m, parentPath, frameId);
            }
        }
        else if (obj instanceof Array && indexedOK) {
            return getAsCfArray(valTracker, (Array)obj, parentPath, frameId);
        }
        else {
            return new IDebugEntity[0];
        }
    }

    private static Comparator<IDebugEntity> xscopeByName = Comparator.comparing((IDebugEntity v) -> v.getName().toLowerCase());

    /**
     * Check if an object is a "noisy" component function that should be hidden in debug output.
     * Uses class name comparison to avoid ClassNotFoundException in OSGi extension mode.
     */
    private static boolean isNoisyComponentFunction(Object obj) {
        String className = obj.getClass().getName();
        // Discard UDFGetterProperty, UDFSetterProperty, UDFImpl (noisy)
        // But retain Lambda and Closure (useful)
        boolean isNoisyUdf = className.equals("lucee.runtime.type.UDFGetterProperty")
            || className.equals("lucee.runtime.type.UDFSetterProperty")
            || className.equals("lucee.runtime.type.UDFImpl");
        boolean isLambdaOrClosure = className.equals("lucee.runtime.type.Lambda")
            || className.equals("lucee.runtime.type.Closure");
        return isNoisyUdf && !isLambdaOrClosure;
    }

    /**
     * Check class by name to avoid ClassNotFoundException in OSGi extension mode.
     * Some Lucee core classes aren't visible to the extension classloader.
     */
    private static boolean isInstanceOf(Object obj, String className) {
        if (obj == null) return false;
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            if (clazz.getName().equals(className)) return true;
            // Check interfaces
            for (Class<?> iface : clazz.getInterfaces()) {
                if (iface.getName().equals(className)) return true;
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    private static IDebugEntity[] getAsMaplike(ValTracker valTracker, Map<String, Object> map, String parentPath, Long frameId) {
        ArrayList<IDebugEntity> results = new ArrayList<>();

        Set<Map.Entry<String,Object>> entries = map.entrySet();

        // We had been showing member functions on component instances, but it's really just noise. Maybe this could be a configurable option.
        final var skipNoisyComponentFunctions = true;

        for (Map.Entry<String, Object> entry : entries) {
            IDebugEntity val = maybeNull_asValue(valTracker, entry.getKey(), entry.getValue(), skipNoisyComponentFunctions, false, parentPath, frameId);
            if (val != null) {
                results.add(val);
            }
        }

        // {
        //     DebugEntity val = new DebugEntity();
        //     val.name = "__luceedebugValueID";
        //     val.value = "" + valTracker.idempotentRegisterObject(map).id;
        //     results.add(val);
        // }

        results.sort(xscopeByName);

        return results.toArray(new IDebugEntity[results.size()]);
    }

    private static IDebugEntity[] getAsCfArray(ValTracker valTracker, Array array, String parentPath, Long frameId) {
        ArrayList<IDebugEntity> result = new ArrayList<>();

        // cf 1-indexed
        for (int i = 1; i <= array.size(); ++i) {
            IDebugEntity val = maybeNull_asValue(valTracker, Integer.toString(i), array.get(i, null), parentPath, frameId);
            if (val != null) {
                result.add(val);
            }
        }

        return result.toArray(new IDebugEntity[result.size()]);
    }

    public IDebugEntity maybeNull_asValue(String name) {
        return maybeNull_asValue(valTracker, name, obj, true, false, null, null);
    }

    /**
     * returns null for "this should not be displayed as a debug entity", which sort of a kludgy way
     * to clean up cfc value info.
     * which is used to cut down on noise from CFC getters/setters/member-functions which aren't too useful for debugging.
     * Maybe such things should be optionally included as per some configuration.
     */
    private static IDebugEntity maybeNull_asValue(ValTracker valTracker, String name, Object obj, String parentPath, Long frameId) {
        return maybeNull_asValue(valTracker, name, obj, true, false, parentPath, frameId);
    }

    /**
     * @markDiscoveredComponentsAsIterableThisRef if true, a Component will be marked as if it were any normal Map<String, Object>. This drives discovery of variables;
     * showing the "top level" of a component we want to show its "inner scopes" (this, variables, and static)
     * @param parentPath The variable path of the parent container (e.g., "local"), or null if not tracked
     * @param frameId The frame ID for setVariable support, or null if not tracked
     */
    private static IDebugEntity maybeNull_asValue(
        ValTracker valTracker,
        String name,
        Object obj,
        boolean skipNoisyComponentFunctions,
        boolean treatDiscoveredComponentsAsScopes,
        String parentPath,
        Long frameId
    ) {
        // Build the full path for this variable
        String childPath = (parentPath != null) ? parentPath + "." + name : null;
        DebugEntity val = new DebugEntity();
        val.name = name;

        if (obj == null) {
            val.value = "<<java-null>>";
        }
        else if (obj instanceof String) {
            val.value = "\"" + obj + "\"";
        }
        else if (obj instanceof Number) {
            val.value = obj.toString();
        }
        else if (obj instanceof Boolean) {
            val.value = obj.toString();
        }
        else if (obj instanceof java.util.Date) {
            val.value = obj.toString();
        }
        else if (obj instanceof Array) {
            int len = ((Array)obj).size();
            val.value = "Array (" + len + ")";
            val.variablesReference = valTracker.registerObjectWithPathAndFrameId(obj, childPath, frameId).id;
        }
        else if (
            /*
                // retain the lambbda/closure types
                var lambda = () => {} // lucee.runtime.type.Lambda
                var closure = function() {} // lucee.runtime.type.Closure

                // discard component function types, they're mostly noise in debug output
                component accessors=true {
                    property name="foo"; // lucee.runtime.type.UDFGetterProperty / lucee.runtime.type.UDFSetterProperty
                    function foo() {} // lucee.runtime.type.UDFImpl
                }
            */
            skipNoisyComponentFunctions
            && isNoisyComponentFunction(obj)
        ) {
            return null;
        }
        else if (isInstanceOf(obj, "lucee.runtime.type.QueryImpl")) {
            // Handle Query - use reflection to avoid ClassNotFoundException in OSGi
            try {
                java.lang.reflect.Method toQueryArrayMethod = Class.forName("lucee.runtime.type.query.QueryArray", true, obj.getClass().getClassLoader())
                    .getMethod("toQueryArray", Class.forName("lucee.runtime.type.QueryImpl", true, obj.getClass().getClassLoader()));
                Object queryAsArrayOfStructs = toQueryArrayMethod.invoke(null, obj);
                java.lang.reflect.Method sizeMethod = queryAsArrayOfStructs.getClass().getMethod("size");
                int size = (int) sizeMethod.invoke(queryAsArrayOfStructs);
                val.value = "Query (" + size + " rows)";

                pin(queryAsArrayOfStructs);

                val.variablesReference = valTracker.registerObjectWithPathAndFrameId(queryAsArrayOfStructs, childPath, frameId).id;
            }
            catch (Throwable e) {
                // Fall back to generic display
                try {
                    val.value = obj.getClass().toString();
                    val.variablesReference = valTracker.registerObjectWithPathAndFrameId(obj, childPath, frameId).id;
                }
                catch (Throwable x) {
                    val.value = "<?> (no string representation available)";
                    val.variablesReference = 0;
                }
            }
        }
        else if (obj instanceof Map) {
            if (obj instanceof Component) {
                val.value = "cfc<" + ((Component)obj).getName() + ">";
                if (treatDiscoveredComponentsAsScopes) {
                    var v = new MarkerTrait.Scope((Component)obj);
                    ((ComponentScopeMarkerTraitShim)obj).__luceedebug__pinComponentScopeMarkerTrait(v);
                    val.variablesReference = valTracker.registerObjectWithPathAndFrameId(v, childPath, frameId).id;
                }
                else {
                    val.variablesReference = valTracker.registerObjectWithPathAndFrameId(obj, childPath, frameId).id;
                }
            }
            else {
                int len = ((Map<?,?>)obj).size();
                val.value = "{} (" + len + " members)";
                val.variablesReference = valTracker.registerObjectWithPathAndFrameId(obj, childPath, frameId).id;
            }
        }
        else {
            try {
                val.value = obj.getClass().toString();
                val.variablesReference = valTracker.registerObjectWithPathAndFrameId(obj, childPath, frameId).id;
            }
            catch (Throwable x) {
                val.value = "<?> (no string representation available)";
                val.variablesReference = 0;
            }
        }

        return val;
    }

    public int getNamedVariablesCount() {
        if (obj instanceof Map) {
            return ((Map<?,?>)obj).size();
        }
        else {
            return 0;
        }
    }

    public int getIndexedVariablesCount() {
        if (isInstanceOf(obj, "lucee.runtime.type.scope.Argument")) {
            // `arguments` scope is both an Array and a Map, which represents the possiblity that a function is called with named args or positional args.
            // It seems like saner default behavior to report it only as having named variables, and zero indexed variables.
            return 0;
        }
        else if (obj instanceof Array) {
            return ((Array)obj).size();
        }
        else {
            return 0;
        }
    }

    /**
     * @return String, or null if there is no path for the underlying entity
     */
    public static String getSourcePath(Object obj) {
        if (obj instanceof Component) {
            return ((Component)obj).getPageSource().getPhyscalFile().getAbsolutePath();
        }
        else if (isInstanceOf(obj, "lucee.runtime.type.UDFImpl")) {
            // Use reflection to avoid ClassNotFoundException in OSGi
            try {
                java.lang.reflect.Field propsField = obj.getClass().getField("properties");
                Object props = propsField.get(obj);
                java.lang.reflect.Method getPageSourceMethod = props.getClass().getMethod("getPageSource");
                Object pageSource = getPageSourceMethod.invoke(props);
                java.lang.reflect.Method getPhyscalFileMethod = pageSource.getClass().getMethod("getPhyscalFile");
                Object file = getPhyscalFileMethod.invoke(pageSource);
                java.lang.reflect.Method getAbsolutePathMethod = file.getClass().getMethod("getAbsolutePath");
                return (String) getAbsolutePathMethod.invoke(file);
            } catch (Throwable e) {
                return null;
            }
        }
        else if (isInstanceOf(obj, "lucee.runtime.type.UDFGSProperty")) {
            // Use reflection to avoid ClassNotFoundException in OSGi
            try {
                java.lang.reflect.Method getPageSourceMethod = obj.getClass().getMethod("getPageSource");
                Object pageSource = getPageSourceMethod.invoke(obj);
                java.lang.reflect.Method getPhyscalFileMethod = pageSource.getClass().getMethod("getPhyscalFile");
                Object file = getPhyscalFileMethod.invoke(pageSource);
                java.lang.reflect.Method getAbsolutePathMethod = file.getClass().getMethod("getAbsolutePath");
                return (String) getAbsolutePathMethod.invoke(file);
            } catch (Throwable e) {
                return null;
            }
        }
        else {
            return null;
        }
    }
}
