package luceedebug;

import java.util.ArrayList;

/**
 * We might be able to whittle this down to just {push,pop,step},
 * which is what instrumented pages need. The other methods are defined in package coreinject,
 * and used only from package coreinject, so the definitely-inside-coreinject use site could
 * probably cast this to the (single!) concrete implementation.
 */
public interface IDebugManager {
    public interface CfStepCallback {
        void call(Thread thread, int minDistanceToLuceedebugBaseFrame);
    }
    
    void spawnWorker(Config config, String jdwpHost, int jdwpPort, String debugHost, int debugPort);
    /**
     * most common frame type
     */
    public void pushCfFrame(lucee.runtime.PageContext pc, String sourceFilePath);
    /**
     * a "default value initialization frame" is the frame that does default function value init,
     * like setting a,b,c in the following:
     * `function foo(a=1,b=2,c=3) {}; foo(42);` <-- init frame will be stepped into twice, once for `b`, once for `c`; `a` is not default init'd
     */
    public void pushCfFunctionDefaultValueInitializationFrame(lucee.runtime.PageContext pageContext, String sourceFilePath);
    public void popCfFrame();

    // these method names are "magic" in that they serve as tags
    // when scanning the stack for "where did we transition from lucee to luceedebug code".
    // These must be the only "entry points" from lucee compiled CF files into luceedebug.
    public void luceedebug_stepNotificationEntry_step(int currentLine);
    public void luceedebug_stepNotificationEntry_stepAfterCompletedUdfCall();

    public void registerStepRequest(Thread thread, int stepType);
    public void clearStepRequest(Thread thread);
    public IDebugFrame[] getCfStack(Thread thread);
    public IDebugEntity[] getScopesForFrame(long frameID);
    public IDebugEntity[] getVariables(long id, IDebugEntity.DebugEntityType maybeNull_whichType);
    public void registerCfStepHandler(CfStepCallback cb);

    public String doDump(ArrayList<Thread> suspendedThreads, int variableID);
    public String doDumpAsJSON(ArrayList<Thread> suspendedThreads, int variableID);

    /**
     * @return String, or null if there is no path for the target ref
     */
    public String getSourcePathForVariablesRef(int variablesRef);

    public Either<String, Either<ICfValueDebuggerBridge, /*primitive value*/String>> evaluate(Long frameID, String expr);
    public boolean evaluateAsBooleanForConditionalBreakpoint(Thread thread, String expr);
}
