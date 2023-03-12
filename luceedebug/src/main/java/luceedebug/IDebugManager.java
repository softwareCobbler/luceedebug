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
    public void pushCfFrame(lucee.runtime.PageContext pc, String filenameAbsPath, int distanceToFrame);
    public void popCfFrame();
    public void step(int depthToFrame, int currentLine);
    public void registerStepRequest(Thread thread, int stepType);
    public void clearStepRequest(Thread thread);
    public IDebugFrame[] getCfStack(Thread thread);
    public IDebugEntity[] getScopesForFrame(long frameID);
    public IDebugEntity[] getVariables(long id);
    public void registerCfStepHandler(CfStepCallback cb);

    public String doDump(ArrayList<Thread> suspendedThreads, int variableID);

    /**
     * @return String, or null if there is no path for the target ref
     */
    public String getSourcePathForVariablesRef(int variablesRef);
}
