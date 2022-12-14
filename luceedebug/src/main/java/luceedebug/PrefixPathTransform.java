package luceedebug;

import java.util.Optional;

class PrefixPathTransform implements ICfPathTransform {
    private final String idePrefix_;
    private final String cfPrefix_;
    public PrefixPathTransform(String idePrefix, String cfPrefix) {
        this.idePrefix_ = idePrefix;
        this.cfPrefix_ = cfPrefix;
    }
    public Optional<String> cfToIde(String s) {
        if (s.startsWith(cfPrefix_)) {
            return Optional.of(s.replace(cfPrefix_, idePrefix_));
        }
        else {
            return Optional.empty();
        }
    }
    public Optional<String> ideToCf(String s) {
        if (s.startsWith(idePrefix_)) {
            return Optional.of(s.replace(idePrefix_, cfPrefix_));
        }
        else {
            return Optional.empty();
        }
    }

    public String asTraceString() {
        return "PrefixPathTransform{idePrefix='" + idePrefix_ + "', cfPrefix='" + cfPrefix_ + "'}";
    }
}
