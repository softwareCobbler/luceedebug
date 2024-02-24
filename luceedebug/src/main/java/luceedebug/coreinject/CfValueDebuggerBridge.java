package luceedebug.coreinject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

import lucee.runtime.Component;
import lucee.runtime.exp.PageException;
import lucee.runtime.type.Array;

import luceedebug.*;

class CfValueDebuggerBridge implements ICfValueDebuggerBridge {
    private final DebugFrame frame;
    public final Object obj;
    public final long id;

    public CfValueDebuggerBridge(DebugFrame frame, Object obj) {
        this.frame = frame;
        this.obj = obj;
        this.id = frame.valTracker.idempotentRegisterObject(obj).id;
    }

    public long getID() {
        return id;
    }

    static class MarkerTrait {
        static class Scope {
            public final Map<?,?> scopelike;
            Scope(Map<?,?> scopelike) {
                this.scopelike = scopelike;
            }
        }
    }

    /**
     * @maybeNull_which --> null means "any type"
     */
    public static IDebugEntity[] getAsDebugEntity(DebugFrame frame, Object obj, IDebugEntity.DebugEntityType maybeNull_which) {
        return getAsDebugEntity(frame.valTracker, obj, maybeNull_which);
    }

    public static IDebugEntity[] getAsDebugEntity(ValTracker valTracker, Object obj, IDebugEntity.DebugEntityType maybeNull_which) {
        final boolean namedOK = maybeNull_which == null || maybeNull_which == IDebugEntity.DebugEntityType.NAMED;
        final boolean indexedOK = maybeNull_which == null || maybeNull_which == IDebugEntity.DebugEntityType.INDEXED;

        if (obj instanceof MarkerTrait.Scope && namedOK) {
            @SuppressWarnings("unchecked")
            var m = (Map<String, Object>)(((MarkerTrait.Scope)obj).scopelike);
            return getAsMaplike(valTracker, m);
        }
        else if (obj instanceof Map && namedOK) {
            if (obj instanceof Component) {
                return new IDebugEntity[] {
                    maybeNull_asValue(valTracker, "this", obj, true, true),
                    maybeNull_asValue(valTracker, "variables", ((Component)obj).getComponentScope()),
                    maybeNull_asValue(valTracker, "static", ((Component)obj).staticScope())
                };
            }
            else {
                @SuppressWarnings("unchecked")
                var m = (Map<String, Object>)obj;
                return getAsMaplike(valTracker, m);
            }
        }
        else if (obj instanceof Array && indexedOK) {
            return getAsCfArray(valTracker, (Array)obj);
        }
        else {
            return new IDebugEntity[0];
        }
    }

    private static Comparator<IDebugEntity> xscopeByName = Comparator.comparing((IDebugEntity v) -> v.getName().toLowerCase());

    private static IDebugEntity[] getAsMaplike(ValTracker valTracker, Map<String, Object> map) {
        ArrayList<IDebugEntity> results = new ArrayList<>();
        
        Set<Map.Entry<String,Object>> entries = map.entrySet();

        // We had been showing member functions on component instances, but it's really just noise. Maybe this could be a configurable option.
        final var skipNoisyComponentFunctions = true;
        
        for (Map.Entry<String, Object> entry : entries) {
            IDebugEntity val = maybeNull_asValue(valTracker, entry.getKey(), entry.getValue(), skipNoisyComponentFunctions, false);
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

    private static IDebugEntity[] getAsCfArray(ValTracker valTracker, Array array) {
        ArrayList<IDebugEntity> result = new ArrayList<>();

        // cf 1-indexed
        for (int i = 1; i <= array.size(); ++i) {
            IDebugEntity val = maybeNull_asValue(valTracker, Integer.toString(i), array.get(i, null));
            if (val != null) {
                result.add(val);
            }
        }

        return result.toArray(new IDebugEntity[result.size()]);
    }

    public IDebugEntity maybeNull_asValue(String name) {
        return maybeNull_asValue(frame.valTracker, name, obj, true, false);
    }

    /**
     * returns null for "this should not be displayed as a debug entity", which sort of a kludgy way
     * to clean up cfc value info.
     * which is used to cut down on noise from CFC getters/setters/member-functions which aren't too useful for debugging.
     * Maybe such things should be optionally included as per some configuration.
     */
    private static IDebugEntity maybeNull_asValue(ValTracker valTracker, String name, Object obj) {
        return maybeNull_asValue(valTracker, name, obj, true, false);
    }

    /**
     * @markDiscoveredComponentsAsIterableThisRef if true, a Component will be marked as if it were any normal Map<String, Object>. This drives discovery of variables;
     * showing the "top level" of a component we want to show its "inner scopes" (this, variables, and static)
     */
    private static IDebugEntity maybeNull_asValue(
        ValTracker valTracker,
        String name,
        Object obj,
        boolean skipNoisyComponentFunctions,
        boolean treatDiscoveredComponentsAsScopes
    ) {
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
            val.variablesReference = valTracker.idempotentRegisterObject(obj).id;
        }
        else if (
            /*
                // retain the lambbda/closure types
                var lambda = () => {} // lucee.runtime.type.Lambda
                var closure = function() {} // lucee.runtime.type.Closure
                // discard component function types, they're mostly noise in debug output
                component accessors=true {
                    property name="foo"; // lucee.runtime.type.UDFGetterProperty / lucee.runtime.type.UDFSetterProperty
                    // lucee.runtime.type.UDFImpl
                    function foo() {}
                }
            */
            skipNoisyComponentFunctions
            && (obj instanceof lucee.runtime.type.UDFGetterProperty
                || obj instanceof lucee.runtime.type.UDFSetterProperty
                || obj instanceof lucee.runtime.type.UDFImpl)
            && !(
                obj instanceof lucee.runtime.type.Lambda
                || obj instanceof lucee.runtime.type.Closure
            )
        ) {
            return null;
        }
        else if (obj instanceof lucee.runtime.type.QueryImpl) {
            try {
                lucee.runtime.type.query.QueryArray queryAsArrayOfStructs = lucee.runtime.type.query.QueryArray.toQueryArray((lucee.runtime.type.QueryImpl)obj);
                val.value = "Query (" + queryAsArrayOfStructs.size() + " rows)";
                val.variablesReference = valTracker.idempotentRegisterObject(obj).id;
            }
            catch (PageException e) {
                //
                // duplicative w/ catch-all else block
                //
                try {
                    val.value = obj.getClass().toString();
                    val.variablesReference = valTracker.idempotentRegisterObject(obj).id;
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
                    val.variablesReference = valTracker.idempotentRegisterObject(v).id;
                }
                else {
                    val.variablesReference = valTracker.idempotentRegisterObject(obj).id;
                }
            }
            else {
                int len = ((Map<?,?>)obj).size();
                val.value = "{} (" + len + " members)";
                val.variablesReference = valTracker.idempotentRegisterObject(obj).id;
            }
        }
        else {
            try {
                val.value = obj.getClass().toString();
                val.variablesReference = valTracker.idempotentRegisterObject(obj).id;
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
        if (obj instanceof lucee.runtime.type.scope.Argument) {
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
        else if (obj instanceof lucee.runtime.type.UDFImpl) {
            return ((lucee.runtime.type.UDFImpl)obj).properties.getPageSource().getPhyscalFile().getAbsolutePath();
        }
        else if (obj instanceof lucee.runtime.type.UDFGSProperty) {
            return ((lucee.runtime.type.UDFGSProperty)obj).getPageSource().getPhyscalFile().getAbsolutePath();
        }
        else {
            return null;
        }
    }
}
