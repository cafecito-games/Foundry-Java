package games.cafecito.foundry.runtime;

import games.cafecito.foundry.types.Variant;
import games.cafecito.foundry.types.VariantCodec;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Host-neutral callable that receives and returns immutable Variant values. */
public final class FoundryCallable implements AutoCloseable {
    private static final int VARIADIC = -1;

    private final int arity;
    private final Function<List<Variant>, Variant> localInvocation;
    private final NativeIdentity nativeIdentity;
    private volatile boolean closed;

    private FoundryCallable(
            int arity,
            Function<List<Variant>, Variant> localInvocation,
            NativeIdentity nativeIdentity) {
        if (arity < VARIADIC) {
            throw new IllegalArgumentException("arity must be nonnegative or variadic");
        }
        this.arity = arity;
        this.localInvocation = localInvocation;
        this.nativeIdentity = nativeIdentity;
        if ((localInvocation == null) == (nativeIdentity == null)) {
            throw new IllegalArgumentException(
                    "Callable must have exactly one local or native backend.");
        }
    }

    public static FoundryCallable fixed(int arity, Function<List<Variant>, Variant> invocation) {
        return new FoundryCallable(arity, Objects.requireNonNull(invocation, "invocation"), null);
    }

    public static FoundryCallable variadic(Function<List<Variant>, Variant> invocation) {
        return new FoundryCallable(
                VARIADIC, Objects.requireNonNull(invocation, "invocation"), null);
    }

    /** Creates a context-bound Callable backed by the native bridge. */
    public static FoundryCallable nativeBacked(
            long contextHandle, long bridgeHandle, int arity, NativeBackend backend) {
        return new FoundryCallable(
                arity,
                null,
                new NativeIdentity(
                        contextHandle,
                        bridgeHandle,
                        Objects.requireNonNull(backend, "backend")));
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

    public boolean isLocal() {
        return nativeIdentity == null;
    }

    public boolean isNativeBacked() {
        return nativeIdentity != null;
    }

    public long nativeContextHandle() {
        return requireNativeIdentity().contextHandle();
    }

    public long nativeBridgeHandle() {
        return requireNativeIdentity().bridgeHandle();
    }

    public Variant call(List<Variant> arguments) {
        List<Variant> checked = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        if (!isVariadic() && checked.size() != arity) {
            throw new IllegalArgumentException(
                    "Callable expected " + arity + " arguments but received " + checked.size());
        }
        if (closed) {
            throw new IllegalStateException("Callable is closed.");
        }
        if (localInvocation != null) {
            return Objects.requireNonNull(localInvocation.apply(checked), "callable result");
        }
        NativeIdentity identity = requireNativeIdentity();
        return Objects.requireNonNull(
                identity
                        .backend()
                        .invoke(identity.contextHandle(), identity.bridgeHandle(), checked),
                "native callable result");
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (nativeIdentity != null) {
            nativeIdentity
                    .backend()
                    .release(nativeIdentity.contextHandle(), nativeIdentity.bridgeHandle());
        }
    }

    private NativeIdentity requireNativeIdentity() {
        if (nativeIdentity == null) {
            throw new IllegalStateException("The local Callable has no native bridge identity.");
        }
        return nativeIdentity;
    }

    /** Narrow delegate used by the Android engine without adding a Callable-specific JNI export. */
    public interface NativeBackend {
        Variant invoke(long contextHandle, long bridgeHandle, List<Variant> arguments);

        void release(long contextHandle, long bridgeHandle);
    }

    private record NativeIdentity(
            long contextHandle, long bridgeHandle, NativeBackend backend) {
        private NativeIdentity {
            if (contextHandle == 0) {
                throw new IllegalArgumentException("Foundry context handle must be nonzero.");
            }
            if (bridgeHandle == 0) {
                throw new IllegalArgumentException("Native Callable handle must be nonzero.");
            }
            Objects.requireNonNull(backend, "backend");
        }
    }
}
