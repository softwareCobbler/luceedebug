package luceedebug;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class PrefixPathTransform implements IPathTransform {
    private final String idePrefix_;
    private final String serverPrefix_;
    private final Pattern idePrefixPattern_;
    private final Pattern serverPrefixPattern_;
    
    public PrefixPathTransform(String idePrefix, String serverPrefix) {
        this.idePrefix_ = idePrefix;
        this.idePrefixPattern_ = asCanonicalFilePrefixPattern(idePrefix);
        this.serverPrefix_ = serverPrefix;
        this.serverPrefixPattern_ = asCanonicalFilePrefixPattern(serverPrefix);
    }

    static Pattern asCanonicalFilePrefixPattern(String s) {
        return Pattern.compile("(?i)^" + Pattern.quote(Config.canonicalizeFileName(s)));
    }

    public Optional<String> serverToIde(String s) {
        return replacePrefix(s, serverPrefixPattern_, idePrefix_);
    }
    public Optional<String> ideToServer(String s) {
        return replacePrefix(s, idePrefixPattern_, serverPrefix_);
    }

    public String asTraceString() {
        return "PrefixPathTransform{idePrefix='" + idePrefix_ + "', serverPrefix='" + serverPrefix_ + "'}";
    }

    private static Optional<String> replacePrefix(String s, Pattern prefixToReplace, String prefix) {
        var canonicalizedS = Config.canonicalizeFileName(s);
        var m = prefixToReplace.matcher(canonicalizedS);
        if (m.find()) {
            String path = m.replaceFirst(Matcher.quoteReplacement(prefix));
            return Optional.of(path);
        }
        return Optional.empty();
    }

}
