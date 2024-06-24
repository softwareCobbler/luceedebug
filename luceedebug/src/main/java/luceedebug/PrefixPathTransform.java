package luceedebug;

import java.util.Optional;

class PrefixPathTransform implements IPathTransform {
    private final String idePrefix_;
    private final String serverPrefix_;
    
    public PrefixPathTransform(String idePrefix, String serverPrefix) {
        this.idePrefix_ = idePrefix;
        this.serverPrefix_ = serverPrefix;
    }

    public Optional<String> serverToIde(String s) {
        return replacePrefix(s, serverPrefix_, idePrefix_);
    }
    public Optional<String> ideToServer(String s) {
        return replacePrefix(s, idePrefix_, serverPrefix_);
    }

    public String asTraceString() {
        return "PrefixPathTransform{idePrefix='" + idePrefix_ + "', serverPrefix='" + serverPrefix_ + "'}";
    }

    private static Optional<String> replacePrefix(String s, String prefixToReplace, String newPrefix) {
        // first we check that the string starts with the prefixToReplace in a case insensitive way
        if (s.toLowerCase().startsWith(prefixToReplace.toLowerCase())) {
            String path = newPrefix + s.substring(prefixToReplace.length());
            return Optional.of(path);
        }
        return Optional.empty();
    }

}
