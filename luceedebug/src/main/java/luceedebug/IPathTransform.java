package luceedebug;

import java.util.Optional;

interface IPathTransform {
    
    /** empty if no match, never null */
    public Optional<String> serverToIde(String s);
    /** empty if no match, never null */
    public Optional<String> ideToServer(String s);

    /**
     * like toString, but contractually for trace purposes
     */
    public String asTraceString();
}
