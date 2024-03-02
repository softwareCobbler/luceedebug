package luceedebug.coreinject;

/**
 * Intended to be an extension on lucee.runtime.ComponentImpl
 * We need to disambiguate between a component meaning "container of a this/variables/static scope" and "literally a this scope"
 * Because we want to show something like the following to the IDE:
 * 
 * someObj : cfc<SomeCfcType>
 *  - variables -> {...}
 *  - this -> {...}
 *  - static -> {...}
 * 
 * But, `someObj` literally is `this` in the above; so we need to know when to show the nested scope listing, and when to show
 * the contents of the `this` scope.
 * 
 * There is only a setter because we just use the setter to pin the disambiguating-wrapper object onto something with a
 * reasonable lifetime to prevent it from being GC'd.
 */
public interface ComponentScopeMarkerTraitShim {
    void __luceedebug__pinComponentScopeMarkerTrait(Object obj);
}
