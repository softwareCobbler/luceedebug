package luceedebug.coreinject;

import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.Optional;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

import lucee.runtime.PageContext;
import luceedebug.Either;
import luceedebug.coreinject.frame.DebugFrame;

import static luceedebug.coreinject.Utils.terminate;

import static lucee.loader.engine.CFMLEngine.DIALECT_CFML;

class ExprEvaluator {
    public static final Optional<Evaluator> lucee5 = Lucee5Evaluator.maybeGet();
    public static final Optional<Evaluator> lucee6 = Lucee6Evaluator.maybeGet();

    static {
        if (lucee5.isEmpty() && lucee6.isEmpty()) {
            System.out.println("[luceedebug] No expression evaluator method found.");
            System.out.println("[luceedebug] Tried:");
            System.out.println("[luceedebug]   lucee.runtime.compiler.Renderer.tag(PageContext,String,int,boolean,boolean) (lucee5 signature)");
            System.out.println("[luceedebug]   lucee.runtime.compiler.Renderer.tag(PageContext,String,boolean,boolean) (lucee6 signature)");
        }
    }

    public static Either</*err*/String, /*ok*/Object> eval(DebugFrame frame, String expr) {
        return lucee5
            .map(v -> v.eval(frame, expr))
            .or(() -> lucee6.map(v -> v.eval(frame, expr)))
            .or(() -> Optional.of(Either.Left("Couldn't find a Lucee engine method to perform evaluation.")))
            .get();
    }

    static abstract class Evaluator {
        // assignment to result var of a name of our choosing is expected safe because:
        //  - prefix shouldn't clash with user variables
        //  - we are synchronized on PageContext by virtue of `doWorkInThisFrame`
        //  - we delete it after grabbing the result
        // At this time, `lucee.runtime.compiler.Renderer.loadPage` will
        // cache compilations based on the hash of the source text; so, using the same result name
        // every time ensures we don't need to recompile a particular expression every time.
        static protected final String resultName = "__luceedebug__evalResult";
        static protected String getEvaluatableSourceText(String expr) {
            return ""
                + "<cfscript>"
                + "try { variables['" + resultName + "'] = {'ok': true, 'result': " + expr + " } }"
                + "catch (any e) { variables['" + resultName + "'] = {'ok': false, 'result': e.message } }"
                + "</cfscript>";
        }

        protected abstract void evalIntoVariablesScope(DebugFrame frame, String expr) throws Throwable;

        public Either</*err*/String, /*ok*/Object> eval(DebugFrame frame, String expr) {
            try {
                evalIntoVariablesScope(frame, expr);
                var obj = consumeResult(frame);
                return mungeResult(obj);
            }
            catch (Throwable e) {
                return Either.Left(e.getMessage());
            }
        }

        /**
         * get the eval'd result out of the frame's variables scope and then delete it from the variables scope.
         */
        private Object consumeResult(DebugFrame frame) throws Throwable {
            Object evalResult = UnsafeUtils.deprecatedScopeGet(frame.getFrameContext().variables, resultName);
            frame.getFrameContext().variables.remove(resultName);
            return evalResult;
        }

        private Either</*err*/String, /*ok*/Object> mungeResult(Object evalResult) {
            if (evalResult instanceof Map) {
                Map<String, Object> struct = UnsafeUtils.uncheckedCast(evalResult);
                var isOk = struct.get("ok");
                var result = struct.get("result");
                if (isOk instanceof Boolean) {
                    if ((Boolean)isOk) {
                        return Either.Right(result);
                    }
                    else {
                        var msg = result instanceof String ? (String)result : "Couldn't evaluate expression - expression threw an exception, but resulting message was non-string";
                        return Either.Left(msg);
                    }
                }
                else {
                    // shouldn't happen
                    var isOkClassName = isOk == null ? "null" : isOk.getClass().getName();
                    return Either.Left("Couldn't evaluate expression - result `ok` property was non-boolean (got " + isOkClassName + ")");
                }
            }
            else {
                // shouldn't happen
                var evalResultClassName = evalResult == null ? "null" : evalResult.getClass().getName();
                return Either.Left("Evaluated expression returned non-Map result of type '" + evalResultClassName + "'");
            }
        }
    }
    
    private static class Lucee5Evaluator extends Evaluator {
        private final MethodHandle methodHandle;
        Lucee5Evaluator(MethodHandle methodHandle) {
            this.methodHandle = methodHandle;
        }

        protected void evalIntoVariablesScope(DebugFrame frame, String expr) throws Throwable {
            methodHandle.invoke(
                /*PageContext pc*/ frame.getFrameContext().pageContext,
                /*String cfml*/ Evaluator.getEvaluatableSourceText(expr),
                /*int dialect*/ DIALECT_CFML,
                /*boolean catchOutput*/ false,
                /*boolean ignoreScopes*/ false
            );
        }

        static Optional<Evaluator> maybeGet() {
            try {
                final MethodType lucee5_evaluateExpr = MethodType.methodType(
                    /*returntype*/lucee.runtime.compiler.Renderer.Result.class,
                    /*PageContext pc*/ PageContext.class,
                    /*String cfml*/ String.class,
                    /*int dialect*/ int.class,
                    /*boolean catchOutput*/ boolean.class,
                    /*boolean ignoreScopes*/ boolean.class
                );
                var methodHandle = MethodHandles
                    .lookup()
                    .findStatic(lucee.runtime.compiler.Renderer.class, "tag", lucee5_evaluateExpr);
                return Optional.of(new Lucee5Evaluator(methodHandle));
            }
            catch (NoSuchMethodException e) {
                return Optional.empty();
            }
            catch (Throwable e) {
                return terminate(e);
            }
        }
    }

    private static class Lucee6Evaluator extends Evaluator {
        private final MethodHandle methodHandle;
        Lucee6Evaluator(MethodHandle methodHandle) {
            this.methodHandle = methodHandle;
        }

        protected void evalIntoVariablesScope(DebugFrame frame, String expr) throws Throwable {
            methodHandle.invoke(
                /*PageContext pc*/ frame.getFrameContext().pageContext,
                /*String cfml*/ Evaluator.getEvaluatableSourceText(expr),
                /*boolean catchOutput*/ false,
                /*boolean ignoreScopes*/ false
            );
        }

        static Optional<Evaluator> maybeGet() {
            try {
                final MethodType lucee6_evaluateExpr = MethodType.methodType(
                    /*returntype*/lucee.runtime.compiler.Renderer.Result.class,
                    /*PageContext pc*/ PageContext.class,
                    /*String cfml*/ String.class,
                    /*boolean catchOutput*/ boolean.class,
                    /*boolean ignoreScopes*/ boolean.class
                );
                var methodHandle = MethodHandles
                    .lookup()
                    .findStatic(lucee.runtime.compiler.Renderer.class, "tag", lucee6_evaluateExpr);
                return Optional.of(new Lucee6Evaluator(methodHandle));
            }
            catch (NoSuchMethodException e) {
                return Optional.empty();
            }
            catch (Throwable e) {
                return terminate(e);
            }
        }
    }
}
