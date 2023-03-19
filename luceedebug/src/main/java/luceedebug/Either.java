package luceedebug;

public class Either<L_t, R_t> {
    private enum Which { left, right };
    private final Which which;
    
    public final L_t left;
    public final R_t right;
    
    private Either(Which which, L_t L, R_t R) {
        if (which == Which.left) {
            this.which = which;
            this.left = L;
            this.right = null;
        }
        else if (which == Which.right) {
            this.which = which;
            this.left = null;
            this.right = R;
        }
        else {
            assert false : "unreachable";
            throw new RuntimeException("unreachable");
        }
    }
    
    public static <L,R> Either<L,R> Left(L v) {
        return new Either<>(Which.left, v, null);
    }

    public static <L,R> Either<L,R> Right(R v) {
        return new Either<>(Which.right, null, v);
    }

    public boolean isLeft() {
        return which == Which.left;
    }
    
    public L_t getLeft() {
        return left;
    }

    public boolean isRight() {
        return which == Which.right;
    }

    public R_t getRight() {
        return right;
    }
}
