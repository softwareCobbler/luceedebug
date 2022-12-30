package luceedebug;

public class OriginalAndTransformedString {
    final public String original;
    final public String transformed;
    public OriginalAndTransformedString(String original, String transformed) {
        System.out.println("OT: " + original + " // " + transformed);
        this.original = original;
        this.transformed = transformed;
    }
}
