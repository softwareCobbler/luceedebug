package luceedebug;

import java.util.Optional;

interface ICfPathTransform {
    
    /** empty if no match, never null */
    public Optional<String> cfToIde(String s);
    /** empty if no match, never null */
    public Optional<String> ideToCf(String s);

    /**
     * like toString, but contractually for trace purposes
     */
    public String asTraceString();
}
