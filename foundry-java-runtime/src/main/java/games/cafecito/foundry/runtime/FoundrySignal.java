package games.cafecito.foundry.runtime;

import games.cafecito.foundry.types.Variant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reentrant, insertion-ordered Java or native-backed signal. */
public final class FoundrySignal implements AutoCloseable {
    private final Object lock = new Object();
    private final Map<Long, FoundryCallable> listeners = new LinkedHashMap<>();
    private final NativeIdentity nativeIdentity;
    private long nextConnection;
    private int activeNativeOperations;
    private boolean closed;
    private boolean nativeReleaseInProgress;
    private boolean nativeReleased;

    public FoundrySignal() {
        nativeIdentity = null;
    }

    private FoundrySignal(NativeIdentity nativeIdentity) {
        this.nativeIdentity = Objects.requireNonNull(nativeIdentity, "nativeIdentity");
    }

    /** Creates a context-bound Signal backed by the native bridge. */
    public static FoundrySignal nativeBacked(
            long contextHandle, long bridgeHandle, NativeBackend backend) {
        return new FoundrySignal(
                new NativeIdentity(
                        contextHandle, bridgeHandle, Objects.requireNonNull(backend, "backend")));
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

    public Connection connect(FoundryCallable callable) {
        FoundryCallable checked = Objects.requireNonNull(callable, "callable");
        NativeIdentity identity;
        synchronized (lock) {
            requireOpen();
            if (nativeIdentity == null) {
                long id = ++nextConnection;
                listeners.put(id, checked);
                return new Connection(id, false);
            }
            identity = beginNativeOperation();
        }
        long connectionHandle;
        try {
            connectionHandle =
                    identity.backend()
                            .connect(identity.contextHandle(), identity.bridgeHandle(), checked);
        } finally {
            NativeOperationCompletion completion = finishNativeOperation();
            releaseNative(completion.release());
        }
        if (connectionHandle == 0) {
            throw new IllegalStateException("Native Signal returned a null connection handle.");
        }
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Signal closed while connecting.");
            }
        }
        return new Connection(connectionHandle, true);
    }

    public void emit(Variant... arguments) {
        List<Variant> values =
                List.copyOf(List.of(Objects.requireNonNull(arguments, "arguments").clone()));
        List<FoundryCallable> snapshot;
        NativeIdentity identity = null;
        synchronized (lock) {
            requireOpen();
            if (nativeIdentity != null) {
                identity = beginNativeOperation();
                snapshot = null;
            } else {
                snapshot = new ArrayList<>(listeners.values());
            }
        }
        if (identity != null) {
            try {
                identity.backend().emit(identity.contextHandle(), identity.bridgeHandle(), values);
            } finally {
                NativeOperationCompletion completion = finishNativeOperation();
                releaseNative(completion.release());
            }
            return;
        }
        for (FoundryCallable listener : snapshot) {
            listener.call(values);
        }
    }

    @Override
    public void close() {
        NativeIdentity release;
        synchronized (lock) {
            if (!closed) {
                closed = true;
                listeners.clear();
            }
            release = reserveNativeRelease();
        }
        releaseNative(release);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Signal is closed.");
        }
    }

    private NativeIdentity requireNativeIdentity() {
        if (nativeIdentity == null) {
            throw new IllegalStateException("The local Signal has no native bridge identity.");
        }
        return nativeIdentity;
    }

    private NativeIdentity beginNativeOperation() {
        activeNativeOperations++;
        return nativeIdentity;
    }

    private NativeOperationCompletion finishNativeOperation() {
        synchronized (lock) {
            activeNativeOperations--;
            return new NativeOperationCompletion(reserveNativeRelease());
        }
    }

    private NativeIdentity reserveNativeRelease() {
        if (closed
                && activeNativeOperations == 0
                && nativeIdentity != null
                && !nativeReleaseInProgress
                && !nativeReleased) {
            nativeReleaseInProgress = true;
            return nativeIdentity;
        }
        return null;
    }

    private void releaseNative(NativeIdentity identity) {
        if (identity == null) {
            return;
        }
        try {
            identity.backend().release(identity.contextHandle(), identity.bridgeHandle());
        } catch (RuntimeException | Error failure) {
            synchronized (lock) {
                nativeReleaseInProgress = false;
            }
            throw failure;
        }
        synchronized (lock) {
            nativeReleaseInProgress = false;
            nativeReleased = true;
        }
    }

    public final class Connection implements AutoCloseable {
        private final long id;
        private final boolean nativeConnection;
        private boolean disconnected;
        private boolean disconnecting;

        private Connection(long id, boolean nativeConnection) {
            this.id = id;
            this.nativeConnection = nativeConnection;
        }

        public boolean isConnected() {
            synchronized (lock) {
                if (disconnected || closed) {
                    return false;
                }
                return nativeConnection || listeners.containsKey(id);
            }
        }

        public void disconnect() {
            NativeIdentity identity;
            synchronized (lock) {
                if (disconnected || disconnecting) {
                    return;
                }
                if (nativeConnection) {
                    if (closed) {
                        disconnected = true;
                        return;
                    }
                    disconnecting = true;
                    identity = beginNativeOperation();
                } else {
                    disconnected = true;
                    listeners.remove(id);
                    return;
                }
            }
            try {
                identity.backend()
                        .disconnect(identity.contextHandle(), identity.bridgeHandle(), id);
                synchronized (lock) {
                    disconnected = true;
                    disconnecting = false;
                }
            } catch (RuntimeException | Error failure) {
                synchronized (lock) {
                    disconnecting = false;
                }
                throw failure;
            } finally {
                NativeOperationCompletion completion = finishNativeOperation();
                releaseNative(completion.release());
            }
        }

        @Override
        public void close() {
            disconnect();
        }
    }

    /** Narrow delegate used by the Android engine without adding Signal-specific JNI exports. */
    public interface NativeBackend {
        long connect(long contextHandle, long signalHandle, FoundryCallable callable);

        void disconnect(long contextHandle, long signalHandle, long connectionHandle);

        void emit(long contextHandle, long signalHandle, List<Variant> arguments);

        void release(long contextHandle, long signalHandle);
    }

    private record NativeIdentity(long contextHandle, long bridgeHandle, NativeBackend backend) {
        private NativeIdentity {
            if (contextHandle == 0) {
                throw new IllegalArgumentException("Foundry context handle must be nonzero.");
            }
            if (bridgeHandle == 0) {
                throw new IllegalArgumentException("Native Signal handle must be nonzero.");
            }
            Objects.requireNonNull(backend, "backend");
        }
    }

    private record NativeOperationCompletion(NativeIdentity release) {}
}
