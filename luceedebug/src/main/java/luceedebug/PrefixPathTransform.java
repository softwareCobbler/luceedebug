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
        if (s.startsWith(serverPrefix_)) {
            return Optional.of(s.replace(serverPrefix_, idePrefix_));
        }
        else {
            return Optional.empty();
        }
    }
    public Optional<String> ideToServer(String s) {
        if (s.startsWith(idePrefix_)) {
            return Optional.of(s.replace(idePrefix_, serverPrefix_));
        }
        else {
            return Optional.empty();
        }
    }

    public String asTraceString() {
        return "PrefixPathTransform{idePrefix='" + idePrefix_ + "', serverPrefix='" + serverPrefix_ + "'}";
    }
}
