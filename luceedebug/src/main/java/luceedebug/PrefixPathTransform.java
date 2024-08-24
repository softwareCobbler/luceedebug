package luceedebug;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class PrefixPathTransform implements IPathTransform {
    //
    // where "unadjusted" means "literally whatever the IDE sent over, no case or path separator adjustments"
    //
    private final String unadjusted_idePrefix;
    private final String unadjusted_serverPrefix;
    private final Pattern caseAndPathSepLenient_idePrefixPattern;
    private final Pattern caseAndPathSepLenient_serverPrefixPattern;
    
    public PrefixPathTransform(String idePrefix, String serverPrefix) {
        this.unadjusted_idePrefix = idePrefix;
        this.caseAndPathSepLenient_idePrefixPattern = asCaseAndPathSepLenientPrefixPattern(idePrefix);
        this.unadjusted_serverPrefix = serverPrefix;
        this.caseAndPathSepLenient_serverPrefixPattern = asCaseAndPathSepLenientPrefixPattern(serverPrefix);
    }

    public Optional<String> serverToIde(String s) {
        return replacePrefix(s, caseAndPathSepLenient_serverPrefixPattern, unadjusted_idePrefix);
    }
    public Optional<String> ideToServer(String s) {
        return replacePrefix(s, caseAndPathSepLenient_idePrefixPattern, unadjusted_serverPrefix);
    }

    public String asTraceString() {
        return "PrefixPathTransform{idePrefix='" + unadjusted_idePrefix + "', serverPrefix='" + unadjusted_serverPrefix + "'}";
    }

    /**
     * A prefix like "foo\bar" will match strings like
     * "foo\bar<suffix>
     * "foo/bar<suffix>
     * "fOO/baR<suffix>"
     * "foO\\bAr<suffix>"
     * "foO/\bAr<suffix>"
     * etc.
     */
    private static Pattern asCaseAndPathSepLenientPrefixPattern(String prefix) {
        var prefixPattern = Arrays
            .stream(prefix.split("[\\\\/]+"))
            .map(v -> Pattern.quote(v))
            .collect(Collectors.joining("[\\\\/]+"));
        return Pattern.compile("(?i)^" + prefixPattern + "(.*)$");
    }

    private static Optional<String> replacePrefix(String unadjustedSource, Pattern lenientPattern, String unadjustedPrefix) {
        var m = lenientPattern.matcher(unadjustedSource);
        if (m.find()) {
            return Optional.of(unadjustedPrefix + m.group(1));
        }
        return Optional.empty();
    }

}
