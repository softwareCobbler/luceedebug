package luceedebug.coreinject.frame;

import luceedebug.IDebugEntity;

/**
 * DummyFrame is just a placeholder for when we wanted to push a frame
 * but were unable to generate one. See notes on `DebugFrame`.
 */
public class DummyFrame extends DebugFrame {
    private static DummyFrame instance = new DummyFrame();
    private DummyFrame() {}

    public static DummyFrame get() {
        return instance;
    }

    private static <T> T fail() {
        throw new RuntimeException("Methods on 'DummyFrame' should never be called.");
    }

    public String getSourceFilePath() { return fail(); };
    public long getId() { return fail(); };
    public String getName() { return fail(); };
    public int getDepth() { return fail(); };
    public int getLine() { return fail(); };
    public void setLine(int line) { fail(); };
    public IDebugEntity[] getScopes() { return fail(); }
}
