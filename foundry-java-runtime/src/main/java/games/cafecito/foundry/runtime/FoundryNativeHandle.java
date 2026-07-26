package games.cafecito.foundry.runtime;

/**
 * Opaque, context-bound bridge handle for a native pointer or structure that is not a Variant
 * value.
 *
 * <p>The numeric handle is meaningful only to the binding context that created it; it is never a
 * process address. The type token prevents one native structure or pointer family from being passed
 * where another is required. A zero bridge handle explicitly represents a null native pointer.
 */
public record FoundryNativeHandle<T>(long contextHandle, Class<T> nativeType, long bridgeHandle) {
    public FoundryNativeHandle {
        if (contextHandle == 0) {
            throw new IllegalArgumentException("Foundry context handle must be nonzero.");
        }
        java.util.Objects.requireNonNull(nativeType, "nativeType");
    }

    public static <T> FoundryNativeHandle<T> of(
            long contextHandle, Class<T> nativeType, long bridgeHandle) {
        return new FoundryNativeHandle<>(contextHandle, nativeType, bridgeHandle);
    }

    public static <T> FoundryNativeHandle<T> nullHandle(long contextHandle, Class<T> nativeType) {
        return of(contextHandle, nativeType, 0);
    }

    public boolean isNull() {
        return bridgeHandle == 0;
    }

    public FoundryNativeHandle<T> requireContext(long expectedContextHandle) {
        if (contextHandle != expectedContextHandle) {
            throw new IllegalArgumentException(
                    "Native handle belongs to context "
                            + contextHandle
                            + ", not "
                            + expectedContextHandle
                            + ".");
        }
        return this;
    }

    public <U> FoundryNativeHandle<T> requireType(Class<U> expectedType) {
        if (nativeType != java.util.Objects.requireNonNull(expectedType, "expectedType")) {
            throw new IllegalArgumentException(
                    "Native handle has type "
                            + nativeType.getName()
                            + ", not "
                            + expectedType.getName()
                            + ".");
        }
        return this;
    }
}
