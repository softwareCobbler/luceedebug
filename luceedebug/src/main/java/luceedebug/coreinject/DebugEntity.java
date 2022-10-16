package luceedebug.coreinject;

import luceedebug.*;

public class DebugEntity implements IDebugEntity {
    String name;
    String value;
    int namedVariables;
    int indexedVariables;
    boolean expensive;
    long variablesReference;

    public String getName() { return name; }
    public String getValue() { return value; }
    public int getNamedVariables() { return namedVariables; }
    public int getIndexedVariables() { return indexedVariables; }
    public boolean getExpensive() { return expensive; }
    public long getVariablesReference() { return variablesReference; }
}
