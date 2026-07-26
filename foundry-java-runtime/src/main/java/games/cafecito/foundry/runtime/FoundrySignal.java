package games.cafecito.foundry.runtime;

import games.cafecito.foundry.types.Variant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reentrant, insertion-ordered Java signal with snapshot emission semantics. */
public final class FoundrySignal {
    private final Object lock = new Object();
    private final Map<Long, FoundryCallable> listeners = new LinkedHashMap<>();
    private long nextConnection;

    public Connection connect(FoundryCallable callable) {
        Objects.requireNonNull(callable, "callable");
        synchronized (lock) {
            long id = ++nextConnection;
            listeners.put(id, callable);
            return new Connection(id);
        }
    }

    public void emit(Variant... arguments) {
        List<Variant> values = List.of(arguments.clone());
        List<FoundryCallable> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(listeners.values());
        }
        for (FoundryCallable listener : snapshot) {
            listener.call(values);
        }
    }

    public final class Connection implements AutoCloseable {
        private final long id;

        private Connection(long id) {
            this.id = id;
        }

        public boolean isConnected() {
            synchronized (lock) {
                return listeners.containsKey(id);
            }
        }

        public void disconnect() {
            synchronized (lock) {
                listeners.remove(id);
            }
        }

        @Override
        public void close() {
            disconnect();
        }
    }
}
