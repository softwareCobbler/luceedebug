package luceedebug.coreinject.frame;

import lucee.runtime.PageContext;
import luceedebug.IDebugFrame;
import luceedebug.coreinject.ValTracker;

/**
 * Should be a sealed class, subtypes are:
 *  - Frame
 *  - DummyFrame
 */
public abstract class DebugFrame implements IDebugFrame {
    // https://github.com/softwareCobbler/luceedebug/issues/68
    // generating a debug frame involves calling into lucee engine code to grab scopes.
    // Lucee can do anything it wants there, and at least when reading from the session scope
    // to deserialize cfcs that had been serialized, can end up invoking more coldfusion code (i.e. calling their
    // pseudoconstructors), which in turn can push more frames, and we want to track those frames, which can push
    // more frames ... and so on. So when we push a frame, we need to know if we are "already pushing a frame",
    // and if so, we just return some dummy frame which is guranteed to NOT schedule more work.
    static private ThreadLocal<Boolean> isPushingFrame = ThreadLocal.withInitial(() -> false);
    
    static public DebugFrame makeFrame(String sourceFilePath, int depth, ValTracker valTracker, PageContext pageContext) {
        if (isPushingFrame.get()) {
            return DummyFrame.get();
        }
        else {
            try {
                isPushingFrame.set(true);
                return new Frame(sourceFilePath, depth, valTracker, pageContext);
            }
            finally {
                isPushingFrame.set(false);
            }
        }
    }
}
