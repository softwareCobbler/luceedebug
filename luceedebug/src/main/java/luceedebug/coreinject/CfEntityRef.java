package luceedebug.coreinject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

import lucee.runtime.type.scope.Scope;
import lucee.runtime.Component;
import lucee.runtime.exp.PageException;
import lucee.runtime.type.Array;

import luceedebug.*;

class CfEntityRef implements ICfEntityRef {
    final String name;
    final ValTracker.Wrapper_t cfEntity; // strong ref

    private long id; // set after we get an id from entity tracker

    @SuppressWarnings("unused")
    private RefTracker.Wrapper_t<CfEntityRef> self_wrapperKeepAlive; // set in local static factory method

    // direct children of this object we want to keep refs to
    private ArrayList<CfEntityRef> owned_keepAlive = new ArrayList<>();

    private RefTracker<CfEntityRef> global_refTracker;
    private ValTracker global_valTracker;

    public long getId() {
        return id;
    }

    private CfEntityRef(String name, ValTracker.Wrapper_t cfEntity, RefTracker<CfEntityRef> refTracker, ValTracker valTracker) {
        this.name = name;
        this.cfEntity = cfEntity;
        this.global_refTracker = refTracker;
        this.global_valTracker = valTracker;
    }

    static public CfEntityRef freshRef(ValTracker valTracker, RefTracker<CfEntityRef> refTracker, String name, Object obj) {
        ValTracker.Wrapper_t underlyingEntity = valTracker.idempotentRegisterObject(obj);
        CfEntityRef self = new CfEntityRef(name, underlyingEntity, refTracker, valTracker);
        RefTracker.Wrapper_t<CfEntityRef> selfRef = refTracker.makeRef(self);

        self.id = selfRef.id;
        self.self_wrapperKeepAlive = selfRef;

        return selfRef.wrapped;
    }

    /**
     * @maybeNull_which --> null means "any type"
     */
    public IDebugEntity[] getAsDebugEntity(IDebugEntity.DebugEntityType maybeNull_which) {
        // reset strong refs, they could change here; discard old refs, maybe we'll get new ones during the method
        owned_keepAlive = new ArrayList<>();

        final boolean namedOK = maybeNull_which == null || maybeNull_which == IDebugEntity.DebugEntityType.NAMED;
        final boolean indexedOK = maybeNull_which == null || maybeNull_which == IDebugEntity.DebugEntityType.INDEXED;

        if (cfEntity.wrapped instanceof Map && namedOK) {
            @SuppressWarnings("unchecked")
            var m = (Map<String, Object>)cfEntity.wrapped;
            return getAsMaplike(m);
        }
        else if (cfEntity.wrapped instanceof Array && indexedOK) {
            return getAsCfArray();
        }
        else {
            return new IDebugEntity[0];
        }
    }

    static private Comparator<IDebugEntity> xscopeByName = Comparator.comparing((IDebugEntity v) -> v.getName().toLowerCase());

    private IDebugEntity[] getAsMaplike(Map<String, Object> map) {
        ArrayList<IDebugEntity> results = new ArrayList<>();
        
        Set<Map.Entry<String,Object>> entries = map.entrySet();

        // We had been showing member functions on component instances, but it's really just noise. Maybe this could be a configurable option.
        final var skipFunctionLikes = true;
        
        for (Map.Entry<String, Object> entry : entries) {
            IDebugEntity val = maybeNull_asValue(entry.getKey(), entry.getValue(), skipFunctionLikes);
            if (val != null) {
                results.add(val);
            }
        }

        results.sort(xscopeByName);    

        return results.toArray(new IDebugEntity[results.size()]);
    }

    private IDebugEntity[] getAsCfArray() {
        Array array = (Array) cfEntity.wrapped;
        ArrayList<IDebugEntity> result = new ArrayList<>();

        // cf 1-indexed
        for (int i = 1; i <= array.size(); ++i) {
            IDebugEntity val = maybeNull_asValue(Integer.toString(i), array.get(i, null));
            if (val != null) {
                result.add(val);
            }
        }

        return result.toArray(new IDebugEntity[result.size()]);
    }

    /**
     * returns null for "this should not be displayed as a debug entity", which sort of a kludgy way
     * to clean up cfc value info.
     * which is used to cut down on noise from CFC getters/setters/member-functions which aren't too useful for debugging.
     * Maybe such things should be optionally included as per some configuration.
     */
    private IDebugEntity maybeNull_asValue(String name, Object cfEntity) {
        return maybeNull_asValue(name, cfEntity, false);
    }

    public IDebugEntity maybeNull_asValue(String name) {
        return maybeNull_asValue(name, cfEntity.wrapped);
    }

    private IDebugEntity maybeNull_asValue(String name, Object cfEntity, boolean skipFunctionLikes) {
        DebugEntity val = new DebugEntity();
        val.name = name;

        if (cfEntity == null) {
            val.value = "<<java-null>>";
        }
        else if (cfEntity instanceof String) {
            val.value = "\"" + cfEntity + "\"";
        }
        else if (cfEntity instanceof Number) {
            val.value = cfEntity.toString();
        }
        else if (cfEntity instanceof Boolean) {
            val.value = cfEntity.toString();
        }
        else if (cfEntity instanceof java.util.Date) {
            val.value = cfEntity.toString();
        }
        else if (cfEntity instanceof Array) {
            CfEntityRef objRef = freshRef(global_valTracker, global_refTracker, name, cfEntity);
            owned_keepAlive.add(objRef);

            int len = ((Array)objRef.cfEntity.wrapped).size();
            val.value = "Array (" + len + ")";
            val.variablesReference = objRef.id;
        }
        else if (
            skipFunctionLikes &&
            (cfEntity instanceof lucee.runtime.type.UDFGetterProperty
            || cfEntity instanceof lucee.runtime.type.UDFSetterProperty
            || cfEntity instanceof lucee.runtime.type.UDFImpl)) {
            return null;
        }
        else if (cfEntity instanceof lucee.runtime.type.QueryImpl) {
            try {
                lucee.runtime.type.query.QueryArray queryAsArrayOfStructs = lucee.runtime.type.query.QueryArray.toQueryArray((lucee.runtime.type.QueryImpl)cfEntity);
                CfEntityRef freshRef = CfEntityRef.freshRef(global_valTracker, global_refTracker, "Query", queryAsArrayOfStructs);
                owned_keepAlive.add(freshRef);
                val.value = "Query (" + queryAsArrayOfStructs.size() + " rows)";
                val.variablesReference = freshRef.getId();
            }
            catch (PageException e) {
                //
                // duplicative w/ catch-all else block
                //
                CfEntityRef objRef = freshRef(global_valTracker, global_refTracker, name, cfEntity);
                owned_keepAlive.add(objRef);

                try {
                    val.value = cfEntity.getClass().toString();
                }
                catch (Throwable x) {
                    val.value = "<?> (no string representation available)";
                }

                val.variablesReference = objRef.id;
            }
        }
        // too broad, will match components and etc.
        else if (cfEntity instanceof Map) {
            CfEntityRef objRef = freshRef(global_valTracker, global_refTracker, name, cfEntity);
            owned_keepAlive.add(objRef);

            int len = ((Map<?,?>)objRef.cfEntity.wrapped).size();
            if (cfEntity instanceof Component) {
                val.value = "cfc<" + ((Component)cfEntity).getName() + ">";
            }
            else {
                val.value = "{} (" + len + " members)";
            }
            val.variablesReference = objRef.id;
        }
        else {
            CfEntityRef objRef = freshRef(global_valTracker, global_refTracker, name, cfEntity);
            owned_keepAlive.add(objRef);

            try {
                val.value = cfEntity.getClass().toString();
            }
            catch (Throwable x) {
                val.value = "<?> (no string representation available)";
            }

            val.variablesReference = objRef.id;
        }

        return val;
    }

    public int getNamedVariablesCount() {
        if (cfEntity.wrapped instanceof Map) {
            return ((Map<?,?>)cfEntity.wrapped).size();
        }
        else {
            return 0;
        }
    }

    public int getIndexedVariablesCount() {
        if (cfEntity.wrapped instanceof lucee.runtime.type.scope.Argument) {
            // `arguments` scope is both an Array and a Map, which represents the possiblity that a function is called with named args or positional args.
            // It seems like saner default behavior to report it only as having named variables, and zero indexed variables.
            return 0;
        }
        else if (cfEntity.wrapped instanceof Array) {
            return ((Array)cfEntity.wrapped).size();
        }
        else {
            return 0;
        }
    }

    /**
     * @return String, or null if there is no path for the underlying entity
     */
    String getSourcePath() {
        final var obj = cfEntity.wrapped;
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
