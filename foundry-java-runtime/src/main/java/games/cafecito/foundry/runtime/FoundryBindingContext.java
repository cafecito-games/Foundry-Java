package games.cafecito.foundry.runtime;

import games.cafecito.foundry.types.Variant;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One live, opaque FoundryExtension binding generation. */
public final class FoundryBindingContext implements AutoCloseable {
    private final long contextHandle;
    private final FoundryEngine engine;
    private final CallbackRegistry callbackRegistry;
    private final Object lifecycleLock = new Object();
    private final Map<ObjectKey, WeakReference<FoundryObject>> wrappers = new HashMap<>();
    private final Set<ObjectLease> leases = new HashSet<>();
    private final Set<Long> invalidatedObjects = new HashSet<>();
    private volatile boolean alive = true;

    public FoundryBindingContext(long contextHandle, FoundryEngine engine) {
        if (contextHandle == 0) {
            throw new IllegalArgumentException("Foundry context handle must be nonzero.");
        }
        this.contextHandle = contextHandle;
        this.engine = Objects.requireNonNull(engine, "engine");
        callbackRegistry = new CallbackRegistry(this);
    }

    public long contextHandle() {
        return contextHandle;
    }

    public FoundryEngine engine() {
        return engine;
    }

    public CallbackRegistry callbackRegistry() {
        return callbackRegistry;
    }

    public Variant call(long objectHandle, String methodIdentity, List<Variant> arguments) {
        Objects.requireNonNull(methodIdentity, "methodIdentity");
        List<Variant> checkedArguments =
                List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        synchronized (lifecycleLock) {
            requireAlive(objectHandle);
            if (objectHandle != 0 && invalidatedObjects.contains(objectHandle)) {
                throw new FoundryObjectDisposedException(contextHandle, objectHandle);
            }
        }
        FoundryEngine.CallResult result =
                Objects.requireNonNull(
                        engine.call(contextHandle, objectHandle, methodIdentity, checkedArguments),
                        "call result");
        if (result.error() != FoundryCallError.OK) {
            throw new FoundryCallException(
                    methodIdentity, result.error(), result.argumentIndex(), result.expectedType());
        }
        return Objects.requireNonNull(result.value(), "call result value");
    }

    public boolean isAlive() {
        return alive;
    }

    public <T extends FoundryObject> T bind(
            long objectHandle,
            ObjectOwnership ownership,
            Class<T> wrapperClass,
            ObjectFactory<T> factory) {
        if (objectHandle == 0) {
            throw new IllegalArgumentException("Foundry object handle must be nonzero.");
        }
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(wrapperClass, "wrapperClass");
        Objects.requireNonNull(factory, "factory");
        synchronized (lifecycleLock) {
            requireAlive(objectHandle);
            if (invalidatedObjects.contains(objectHandle)) {
                throw new FoundryObjectDisposedException(contextHandle, objectHandle);
            }
            if (!engine.isObjectValid(contextHandle, objectHandle)) {
                throw new FoundryObjectDisposedException(contextHandle, objectHandle);
            }
            ObjectKey key = new ObjectKey(objectHandle, wrapperClass);
            WeakReference<FoundryObject> reference = wrappers.get(key);
            FoundryObject cached = reference == null ? null : reference.get();
            if (cached != null && cached.isAlive()) {
                return wrapperClass.cast(cached);
            }

            ObjectLease lease =
                    new ObjectLease(contextHandle, objectHandle, ownership, engine, this::isAlive);
            try {
                T wrapper = Objects.requireNonNull(factory.create(this, lease), "factory result");
                wrappers.put(key, new WeakReference<>(wrapper));
                leases.add(lease);
                return wrapper;
            } catch (Throwable failure) {
                lease.run();
                throw failure;
            }
        }
    }

    public void invalidateObject(long objectHandle) {
        if (objectHandle == 0) {
            return;
        }
        synchronized (lifecycleLock) {
            invalidatedObjects.add(objectHandle);
            Iterator<Map.Entry<ObjectKey, WeakReference<FoundryObject>>> iterator =
                    wrappers.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<ObjectKey, WeakReference<FoundryObject>> entry = iterator.next();
                if (entry.getKey().objectHandle == objectHandle) {
                    FoundryObject wrapper = entry.getValue().get();
                    if (wrapper != null) {
                        wrapper.invalidate();
                    }
                    iterator.remove();
                }
            }
            for (ObjectLease lease : leases) {
                if (lease.objectHandle() == objectHandle) {
                    lease.invalidate();
                }
            }
        }
    }

    @Override
    public void close() {
        if (!callbackRegistry.disable()) {
            return;
        }
        Set<ObjectLease> toClose;
        synchronized (lifecycleLock) {
            if (!alive) {
                return;
            }
            alive = false;
            wrappers.clear();
            toClose = Set.copyOf(leases);
            leases.clear();
            for (ObjectLease lease : toClose) {
                lease.invalidate();
            }
        }
        for (ObjectLease lease : toClose) {
            lease.run();
        }
    }

    private void requireAlive(long objectHandle) {
        if (!alive) {
            throw new FoundryObjectDisposedException(contextHandle, objectHandle);
        }
    }

    @FunctionalInterface
    public interface ObjectFactory<T extends FoundryObject> {
        T create(FoundryBindingContext context, ObjectLease lease);
    }

    private record ObjectKey(long objectHandle, Class<? extends FoundryObject> wrapperClass) {}
}
