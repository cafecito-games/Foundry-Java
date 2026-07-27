package games.cafecito.foundry.runtime;

/**
 * Opaque, context-bound bridge handle for a native pointer or structure that is not a Variant
 * value.
 *
 * <p>The numeric handle is meaningful only to the binding context that created it; it is never a
 * process address. The type token prevents one native structure or pointer family from being passed
 * where another is required. A zero bridge handle explicitly represents a null native pointer.
 */
public final class FoundryNativeHandle<T> implements AutoCloseable {
    private final long contextHandle;
    private final Class<T> nativeType;
    private final long bridgeHandle;
    private final FoundryEngine owner;
    private boolean closed;
    private boolean releaseInProgress;
    private boolean released;

    public FoundryNativeHandle(long contextHandle, Class<T> nativeType, long bridgeHandle) {
        this(contextHandle, nativeType, bridgeHandle, null);
    }

    private FoundryNativeHandle(
            long contextHandle, Class<T> nativeType, long bridgeHandle, FoundryEngine owner) {
        if (contextHandle == 0) {
            throw new IllegalArgumentException("Foundry context handle must be nonzero.");
        }
        this.contextHandle = contextHandle;
        this.nativeType = java.util.Objects.requireNonNull(nativeType, "nativeType");
        this.bridgeHandle = bridgeHandle;
        this.owner = owner;
    }

    public static <T> FoundryNativeHandle<T> of(
            long contextHandle, Class<T> nativeType, long bridgeHandle) {
        return new FoundryNativeHandle<>(contextHandle, nativeType, bridgeHandle);
    }

    public static <T> FoundryNativeHandle<T> owned(
            long contextHandle, Class<T> nativeType, long bridgeHandle, FoundryEngine owner) {
        return new FoundryNativeHandle<>(
                contextHandle,
                nativeType,
                bridgeHandle,
                java.util.Objects.requireNonNull(owner, "owner"));
    }

    public static <T> FoundryNativeHandle<T> nullHandle(long contextHandle, Class<T> nativeType) {
        return of(contextHandle, nativeType, 0);
    }

    public boolean isNull() {
        return bridgeHandle == 0;
    }

    public long contextHandle() {
        return contextHandle;
    }

    public Class<T> nativeType() {
        return nativeType;
    }

    public long bridgeHandle() {
        return bridgeHandle;
    }

    public synchronized boolean isClosed() {
        return closed;
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

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            if (owner == null || bridgeHandle == 0 || released || releaseInProgress) {
                return;
            }
            releaseInProgress = true;
        }
        try {
            owner.release(contextHandle, bridgeHandle);
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                releaseInProgress = false;
            }
            throw failure;
        }
        synchronized (this) {
            releaseInProgress = false;
            released = true;
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof FoundryNativeHandle<?> handle
                        && contextHandle == handle.contextHandle
                        && nativeType == handle.nativeType
                        && bridgeHandle == handle.bridgeHandle);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(contextHandle, nativeType, bridgeHandle);
    }

    @Override
    public String toString() {
        return "FoundryNativeHandle[contextHandle="
                + contextHandle
                + ", nativeType="
                + nativeType
                + ", bridgeHandle="
                + bridgeHandle
                + "]";
    }
}
