package luceedebug;

import java.util.Optional;
import java.util.function.Function;

public class Either<L, R> {
    private enum Which { left, right };
    
    private final Which which;    
    public final L left;
    public final R right;
    
    private Either(Which which, L L, R R) {
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

    /**
     * Left biased (that is, if optional is engaged, returns a Left; otherwise, a Right).
     */
    public static <T> Either<T, Void> fromOpt(Optional<T> opt) {
        return opt.isPresent() ? Either.Left(opt.get()) : Either.Right(null);
    }

    public boolean isLeft() {
        return which == Which.left;
    }
    
    public L getLeft() {
        return left;
    }

    public boolean isRight() {
        return which == Which.right;
    }

    public R getRight() {
        return right;
    }

    // Either a b -> (a -> x) -> (b -> y) -> Either x y
    public <L2, R2> Either<L2,R2> bimap(Function<L, L2> l,  Function<R, R2> r) {
        return isLeft()
            ? Left(l.apply(getLeft()))
            : Right(r.apply(getRight()));
    }

    // Either a b -> (a -> c) -> (b -> c) -> c
    public <Result> Result collapse(Function<L, Result> l, Function<R, Result> r) {
        return isLeft()
            ? l.apply(getLeft())
            : r.apply(getRight());
    }
}
