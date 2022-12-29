package luceedebug;

import java.util.Optional;

class PrefixPathTransform implements IPathTransform {
    private final Config config_;
    private final String idePrefix_;
    private final String serverPrefix_;
    // regexp ... case sensitive optionally ... with ^(i)...
    public PrefixPathTransform(Config config, String idePrefix, String serverPrefix) {
        this.config_ = config;
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
