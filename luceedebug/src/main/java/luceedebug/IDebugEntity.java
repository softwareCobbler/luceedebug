package luceedebug;

public interface IDebugEntity {
    public static enum DebugEntityType {
        NAMED, INDEXED
    }

    public String getName();
    public String getValue();
    public int getNamedVariables();
    public int getIndexedVariables();
    public boolean getExpensive();
    public long getVariablesReference();
}
