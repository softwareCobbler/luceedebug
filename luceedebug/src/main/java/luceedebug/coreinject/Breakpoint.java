package luceedebug.coreinject;

import luceedebug.*;

class Breakpoint implements IBreakpoint {
    final int line;
    final int ID;
    final boolean isBound;

    private Breakpoint(int line, int ID, boolean isBound) {
        this.line = line;
        this.ID = ID;
        this.isBound = isBound;
    }

    public static Breakpoint Bound(int line, int ID) {
        return new Breakpoint(line, ID, true);
    }

    public static Breakpoint Unbound(int line, int ID) {
        return new Breakpoint(line, ID, false);
    }

    public int getLine() { return line; }
    public int getID() { return ID; }
    public boolean getIsBound() { return isBound; }
}
