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
        void call(Thread thread, int distanceToJvmFrame);
    }
    
    void spawnWorker(Config config, String jdwpHost, int jdwpPort, String debugHost, int debugPort);
    /**
     * most common frame type
     */
    public void pushCfFrame(lucee.runtime.PageContext pc, String filenameAbsPath, int distanceToFrame);
    /**
     * a "default value initialization frame" is the frame that does default function value init,
     * like setting a,b,c in the following:
     * `function foo(a=1,b=2,c=3) {}; foo(42);` <-- init frame will be stepped into twice, once for `b`, once for `c`; `a` is not default init'd
     */
    public void pushCfFunctionDefaultValueInitializationFrame(lucee.runtime.PageContext pageContext, String sourceFilePath, int distanceToActualFrame);
    public void popCfFrame();
    public void step(int depthToFrame, int currentLine);
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

    public Either<ICfEntityRef, /*primitive value*/String> evaluate(Long frameID, String expr);
}
