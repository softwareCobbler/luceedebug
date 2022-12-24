package luceedebug;

import java.util.Optional;

class IdentityPathTransform implements IPathTransform {
    public Optional<String> serverToIde(String s) { return Optional.of(s); }
    public Optional<String> ideToServer(String s) { return Optional.of(s); }

    public String asTraceString() {
        return "IdentityPathTransform";
    }
}
