package luceedebug;

import java.util.Optional;

class IdentityPathTransform implements ICfPathTransform {
    public Optional<String> cfToIde(String s) { return Optional.of(s); }
    public Optional<String> ideToCf(String s) { return Optional.of(s); }

    public String asTraceString() {
        return "IdentityPathTransform";
    }
}
