package luceedebug;

class PrefixPathTransform implements ICfPathTransform {
    private final String idePrefix_;
    private final String cfPrefix_;
    public PrefixPathTransform(String idePrefix, String cfPrefix) {
        this.idePrefix_ = idePrefix;
        this.cfPrefix_ = cfPrefix;
    }
    public String cfToIde(String s) {
        if (s.startsWith(cfPrefix_)) {
            return s.replace(cfPrefix_, idePrefix_);
        }
        else {
            return s;
        }
    }
    public String ideToCf(String s) {
        if (s.startsWith(idePrefix_)) {
            return s.replace(idePrefix_, cfPrefix_);
        }
        else {
            return s;
        }
    }

    public String asTraceString() {
        return "PrefixPathTransform{idePrefix='" + idePrefix_ + "', cfPrefix='" + cfPrefix_ + "'}";
    }
}
