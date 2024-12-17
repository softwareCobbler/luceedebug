package luceedebug.coreinject;

import luceedebug.*;
import luceedebug.StrongInt.DapBreakpointID;

class Breakpoint implements IBreakpoint {
    final int line;
    final DapBreakpointID ID;
    final boolean isBound;

    private Breakpoint(int line, DapBreakpointID ID, boolean isBound) {
        this.line = line;
        this.ID = ID;
        this.isBound = isBound;
    }

    public static Breakpoint Bound(int line, DapBreakpointID ID) {
        return new Breakpoint(line, ID, true);
    }

    public static Breakpoint Unbound(int line, DapBreakpointID ID) {
        return new Breakpoint(line, ID, false);
    }

    public int getLine() { return line; }
    public int getID() { return ID.v; }
    public boolean getIsBound() { return isBound; }
}
