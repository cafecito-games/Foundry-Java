package games.cafecito.foundry.runtime;

import games.cafecito.foundry.types.Variant;
import games.cafecito.foundry.types.VariantCodec;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Host-neutral callable that receives and returns immutable Variant values. */
public final class FoundryCallable {
    private static final int VARIADIC = -1;

    private final int arity;
    private final Function<List<Variant>, Variant> invocation;

    private FoundryCallable(int arity, Function<List<Variant>, Variant> invocation) {
        if (arity < VARIADIC) {
            throw new IllegalArgumentException("arity must be nonnegative or variadic");
        }
        this.arity = arity;
        this.invocation = Objects.requireNonNull(invocation, "invocation");
    }

    public static FoundryCallable fixed(int arity, Function<List<Variant>, Variant> invocation) {
        return new FoundryCallable(arity, invocation);
    }

    public static FoundryCallable variadic(Function<List<Variant>, Variant> invocation) {
        return new FoundryCallable(VARIADIC, invocation);
    }

    public static <T, R> FoundryCallable unary(
            VariantCodec<T> argumentCodec, VariantCodec<R> resultCodec, Function<T, R> invocation) {
        Objects.requireNonNull(argumentCodec, "argumentCodec");
        Objects.requireNonNull(resultCodec, "resultCodec");
        Objects.requireNonNull(invocation, "invocation");
        return fixed(
                1,
                arguments ->
                        resultCodec.encode(
                                invocation.apply(argumentCodec.decode(arguments.get(0)))));
    }

    public int arity() {
        return arity;
    }

    public boolean isVariadic() {
        return arity == VARIADIC;
    }

    public Variant call(List<Variant> arguments) {
        List<Variant> checked = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        if (!isVariadic() && checked.size() != arity) {
            throw new IllegalArgumentException(
                    "Callable expected " + arity + " arguments but received " + checked.size());
        }
        return Objects.requireNonNull(invocation.apply(checked), "callable result");
    }
}
