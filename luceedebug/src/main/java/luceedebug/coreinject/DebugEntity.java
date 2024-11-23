package luceedebug.coreinject;

import luceedebug.*;

public class DebugEntity implements IDebugEntity {
    public String name;
    public String value;
    public int namedVariables;
    public int indexedVariables;
    public boolean expensive;
    public long variablesReference;

    public String getName() { return name; }
    public String getValue() { return value; }
    public int getNamedVariables() { return namedVariables; }
    public int getIndexedVariables() { return indexedVariables; }
    public boolean getExpensive() { return expensive; }
    public long getVariablesReference() { return variablesReference; }
}
