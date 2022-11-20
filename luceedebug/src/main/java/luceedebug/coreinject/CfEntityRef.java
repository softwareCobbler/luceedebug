package luceedebug.coreinject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

import lucee.runtime.type.scope.Scope;
import lucee.runtime.Component;
import lucee.runtime.type.Array;

import luceedebug.*;

class CfEntityRef {
    final String name;
    final ValTracker.Wrapper_t cfEntity; // strong ref

    private long id; // set after we get an id from entity tracker

    @SuppressWarnings("unused")
    private RefTracker.Wrapper_t<CfEntityRef> self_wrapperKeepAlive; // set in local static factory method

    // direct children of this object we want to keep refs to
    private ArrayList<CfEntityRef> owned_keepAlive = new ArrayList<>();

    private RefTracker<CfEntityRef> global_refTracker;
    private ValTracker global_valTracker;

    long getId() {
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

    public IDebugEntity[] getAsDebugEntity() {
        // reset strong refs, they could change here; discard old refs, maybe we'll get new ones during the method
        owned_keepAlive = new ArrayList<>();

        if (cfEntity.wrapped instanceof Component) {
            @SuppressWarnings("unchecked")
            var m = (Map<String, Object>)((Component)cfEntity.wrapped).getComponentScope();
            return getAsMaplike(m);
        }
        if (cfEntity.wrapped instanceof Map) {
            @SuppressWarnings("unchecked")
            var m = (Map<String, Object>)cfEntity.wrapped;
            return getAsMaplike(m);
        }
        else if (cfEntity.wrapped instanceof Array) {
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
        
        for (Map.Entry<String, Object> entry : entries) {
            IDebugEntity val = asValue(entry.getKey(), entry.getValue());
            results.add(val);
        }

        results.sort(xscopeByName);    

        return results.toArray(new IDebugEntity[results.size()]);
    }

    private IDebugEntity[] getAsCfArray() {
        Array array = (Array) cfEntity.wrapped;
        ArrayList<IDebugEntity> result = new ArrayList<>();

        // cf 1-indexed
        for (int i = 1; i <= array.size(); ++i) {
            IDebugEntity val = asValue(Integer.toString(i), array.get(i, null));
            result.add(val);
        }

        return result.toArray(new IDebugEntity[result.size()]);
    }

    private IDebugEntity asValue(String name, Object cfEntity) {
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
        else if (cfEntity instanceof Array) {
            CfEntityRef objRef = freshRef(global_valTracker, global_refTracker, name, cfEntity);
            owned_keepAlive.add(objRef);

            int len = ((Array)objRef.cfEntity.wrapped).size();
            val.value = "Array (" + len + ")";
            val.variablesReference = objRef.id;
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
                val.value = "<?> " + cfEntity.toString();
            }
            catch (Throwable x) {
                val.value = "<?> (no string representation available)";
            }

            val.variablesReference = objRef.id;
        }

        return val;
    }

    int getNamedVariablesCount() {
        if (cfEntity instanceof Map) {
            return ((Map<?,?>)cfEntity).size();
        }
        return 0;
    }

    int getIndexedVariablesCount() {
        if (cfEntity instanceof Array) {
            return ((Array)cfEntity).size();
        }
        return 0;
    }
}
