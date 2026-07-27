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
    private boolean closed;

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
                        contextHandle,
                        bridgeHandle,
                        Objects.requireNonNull(backend, "backend")));
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
        synchronized (lock) {
            requireOpen();
            if (nativeIdentity == null) {
                long id = ++nextConnection;
                listeners.put(id, checked);
                return new Connection(id, false);
            }
            long connectionHandle =
                    nativeIdentity
                            .backend()
                            .connect(
                                    nativeIdentity.contextHandle(),
                                    nativeIdentity.bridgeHandle(),
                                    checked);
            if (connectionHandle == 0) {
                throw new IllegalStateException("Native Signal returned a null connection handle.");
            }
            return new Connection(connectionHandle, true);
        }
    }

    public void emit(Variant... arguments) {
        List<Variant> values =
                List.copyOf(List.of(Objects.requireNonNull(arguments, "arguments").clone()));
        List<FoundryCallable> snapshot;
        synchronized (lock) {
            requireOpen();
            if (nativeIdentity != null) {
                nativeIdentity
                        .backend()
                        .emit(
                                nativeIdentity.contextHandle(),
                                nativeIdentity.bridgeHandle(),
                                values);
                return;
            }
            snapshot = new ArrayList<>(listeners.values());
        }
        for (FoundryCallable listener : snapshot) {
            listener.call(values);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            listeners.clear();
            if (nativeIdentity != null) {
                nativeIdentity
                        .backend()
                        .release(
                                nativeIdentity.contextHandle(), nativeIdentity.bridgeHandle());
            }
        }
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

    public final class Connection implements AutoCloseable {
        private final long id;
        private final boolean nativeConnection;
        private boolean disconnected;

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
            synchronized (lock) {
                if (disconnected) {
                    return;
                }
                disconnected = true;
                if (nativeConnection) {
                    NativeIdentity identity = requireNativeIdentity();
                    identity
                            .backend()
                            .disconnect(
                                    identity.contextHandle(), identity.bridgeHandle(), id);
                } else {
                    listeners.remove(id);
                }
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

    private record NativeIdentity(
            long contextHandle, long bridgeHandle, NativeBackend backend) {
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
}
