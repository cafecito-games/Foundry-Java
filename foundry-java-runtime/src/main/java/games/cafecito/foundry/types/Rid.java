package games.cafecito.foundry.types;

import java.util.Objects;

/**
 * Opaque Foundry resource identifier with idempotent native lifetime ownership.
 *
 * <p>Locally-created values may only encode the invalid zero RID. Nonzero native values retain
 * their context-bound bridge identity so they can be copied without interpreting engine-private RID
 * storage.
 */
public final class Rid implements AutoCloseable {
    private final long id;
    private final long nativeContextHandle;
    private final long nativeBridgeHandle;
    private final NativeBackend nativeBackend;
    private boolean closed;
    private boolean nativeReleaseInProgress;
    private boolean nativeReleased;

    public Rid(long id) {
        if (id < 0) {
            throw new IllegalArgumentException("RID must be nonnegative: " + id);
        }
        this.id = id;
        nativeContextHandle = 0;
        nativeBridgeHandle = 0;
        nativeBackend = null;
    }

    private Rid(long nativeContextHandle, long nativeBridgeHandle, NativeBackend nativeBackend) {
        if (nativeContextHandle <= 0 || nativeBridgeHandle <= 0) {
            throw new IllegalArgumentException(
                    "Native RID context and bridge handles must be positive.");
        }
        id = 0;
        this.nativeContextHandle = nativeContextHandle;
        this.nativeBridgeHandle = nativeBridgeHandle;
        this.nativeBackend = Objects.requireNonNull(nativeBackend, "nativeBackend");
    }

    public static Rid nativeBacked(
            long contextHandle, long bridgeHandle, NativeBackend nativeBackend) {
        return new Rid(contextHandle, bridgeHandle, nativeBackend);
    }

    public long id() {
        if (isNativeBacked()) {
            throw new IllegalStateException("Native-backed RID has no public numeric identity.");
        }
        return id;
    }

    public boolean isNativeBacked() {
        return nativeBridgeHandle != 0;
    }

    public long nativeContextHandle() {
        requireNative();
        return nativeContextHandle;
    }

    public long nativeBridgeHandle() {
        requireNative();
        return nativeBridgeHandle;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (!isNativeBacked()) {
                return;
            }
            closed = true;
            if (nativeReleased || nativeReleaseInProgress) {
                return;
            }
            nativeReleaseInProgress = true;
        }
        try {
            nativeBackend.release(nativeContextHandle, nativeBridgeHandle);
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                nativeReleaseInProgress = false;
            }
            throw failure;
        }
        synchronized (this) {
            nativeReleaseInProgress = false;
            nativeReleased = true;
        }
    }

    private void requireNative() {
        if (!isNativeBacked()) {
            throw new IllegalStateException("RID is Java-local.");
        }
        if (closed) {
            throw new IllegalStateException("RID is closed.");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Rid rid)) {
            return false;
        }
        return id == rid.id
                && nativeContextHandle == rid.nativeContextHandle
                && nativeBridgeHandle == rid.nativeBridgeHandle;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, nativeContextHandle, nativeBridgeHandle);
    }

    @Override
    public String toString() {
        return isNativeBacked()
                ? "Rid[nativeContextHandle="
                        + nativeContextHandle
                        + ", nativeBridgeHandle="
                        + nativeBridgeHandle
                        + "]"
                : "Rid[id=" + id + "]";
    }

    @FunctionalInterface
    public interface NativeBackend {
        void release(long contextHandle, long bridgeHandle);
    }
}
