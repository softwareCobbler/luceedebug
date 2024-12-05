package luceedebug;

public class OriginalAndTransformedString {
    final public String original;
    final public String transformed;
    public OriginalAndTransformedString(String original, String transformed) {
        this.original = original;
        this.transformed = transformed;
    }

    @Override
    public boolean equals(Object v) {
        if (v instanceof OriginalAndTransformedString) {
            var other = (OriginalAndTransformedString)v;
            return other.original.equals(original) && other.transformed.equals(transformed);
        }
        return false;
    }
}
