package luceedebug;

public interface IDebugEntity {
    public String getName();
    public String getValue();
    public int getNamedVariables();
    public int getIndexedVariables();
    public boolean getExpensive();
    public long getVariablesReference();
}
