package luceedebug.coreinject;

/**
 * Intended to be an extension on lucee.runtime.type.scope.ClosureScope, applied during classfile rewrites during agent startup.
 */
public interface ClosureScopeLocalScopeAccessorShim {
    lucee.runtime.type.scope.Scope getLocalScope();
}
