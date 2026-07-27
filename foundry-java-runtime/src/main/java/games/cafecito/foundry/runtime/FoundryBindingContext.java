package games.cafecito.foundry.runtime;

import games.cafecito.foundry.types.Variant;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
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
    private final Map<Long, WeakReference<FoundryObject>> wrappers = new HashMap<>();
    private final Map<String, WrapperRegistration<?>> wrapperRegistrations = new HashMap<>();
    private final Map<String, String> foundryTypesByJavaName = new HashMap<>();
    private Map<String, String> extensionFoundryTypesByJavaName = Map.of();
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
        if (engine instanceof FoundryBindingContextAware contextAware) {
            contextAware.attachBindingContext(this);
        }
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

    public <T extends FoundryObject> void registerObjectType(
            String foundryType, Class<T> wrapperClass, ObjectFactory<T> factory) {
        registerObjectType(foundryType, ObjectOwnership.BORROWED, wrapperClass, factory);
    }

    public <T extends FoundryObject> void registerObjectType(
            String foundryType,
            ObjectOwnership ownership,
            Class<T> wrapperClass,
            ObjectFactory<T> factory) {
        if (foundryType == null || foundryType.isBlank()) {
            throw new IllegalArgumentException("Foundry object type must not be blank.");
        }
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(wrapperClass, "wrapperClass");
        Objects.requireNonNull(factory, "factory");
        synchronized (lifecycleLock) {
            requireAlive(0);
            WrapperRegistration<?> previous = wrapperRegistrations.get(foundryType);
            if (previous != null && previous.wrapperClass() != wrapperClass) {
                throw new IllegalStateException(
                        "Foundry object type " + foundryType + " is already registered.");
            }
            String javaName = wrapperClass.getName();
            String previousFoundryType = foundryTypesByJavaName.get(javaName);
            if (previousFoundryType != null && !previousFoundryType.equals(foundryType)) {
                throw new IllegalStateException(
                        "Java object type " + javaName + " is already registered.");
            }
            wrapperRegistrations.putIfAbsent(
                    foundryType, new WrapperRegistration<>(ownership, wrapperClass, factory));
            foundryTypesByJavaName.putIfAbsent(javaName, foundryType);
        }
    }

    public String foundryTypeForJavaName(String javaName) {
        if (javaName == null || javaName.isBlank()) {
            return null;
        }
        synchronized (lifecycleLock) {
            requireAlive(0);
            String extensionType = extensionFoundryTypesByJavaName.get(javaName);
            return extensionType == null ? foundryTypesByJavaName.get(javaName) : extensionType;
        }
    }

    void publishRegistrationCatalog(List<FoundryClassDescriptor> descriptors) {
        Objects.requireNonNull(descriptors, "descriptors");
        Map<String, String> catalog = new HashMap<>();
        for (FoundryClassDescriptor descriptor : descriptors) {
            FoundryClassDescriptor checked = Objects.requireNonNull(descriptor, "descriptor");
            String previous = catalog.putIfAbsent(checked.javaName(), checked.foundryName());
            if (previous != null && !previous.equals(checked.foundryName())) {
                throw new IllegalArgumentException(
                        "Java extension type "
                                + checked.javaName()
                                + " has ambiguous Foundry names.");
            }
        }
        Map<String, String> immutableCatalog = Map.copyOf(catalog);
        synchronized (lifecycleLock) {
            requireAlive(0);
            if (!extensionFoundryTypesByJavaName.isEmpty()
                    && !extensionFoundryTypesByJavaName.equals(immutableCatalog)) {
                throw new IllegalStateException(
                        "Foundry extension registration catalog is already published.");
            }
            extensionFoundryTypesByJavaName = immutableCatalog;
        }
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
        T created = null;
        ObjectLease.Transition failureTransition = null;
        Throwable factoryFailure = null;
        synchronized (lifecycleLock) {
            requireAlive(objectHandle);
            if (invalidatedObjects.contains(objectHandle)) {
                throw new FoundryObjectDisposedException(contextHandle, objectHandle);
            }
            if (!engine.isObjectValid(contextHandle, objectHandle)) {
                throw new FoundryObjectDisposedException(contextHandle, objectHandle);
            }
            WeakReference<FoundryObject> reference = wrappers.get(objectHandle);
            FoundryObject cached = reference == null ? null : reference.get();
            WrapperRegistration<?> resolved = resolveRegistration(objectHandle);
            ObjectOwnership effectiveOwnership =
                    ownership == ObjectOwnership.OWNED
                            ? ObjectOwnership.OWNED
                            : resolved != null
                                            && resolved.ownership()
                                                    == ObjectOwnership.REFERENCE_COUNTED
                                    ? ObjectOwnership.REFERENCE_COUNTED
                                    : ownership;
            if (cached != null && cached.lease().isMarkedAlive()) {
                if (!wrapperClass.isInstance(cached)) {
                    throw incompatibleWrapper(objectHandle, wrapperClass, cached.getClass());
                }
                cached.lease().upgrade(effectiveOwnership);
                return wrapperClass.cast(cached);
            }

            if (resolved != null && !wrapperClass.isAssignableFrom(resolved.wrapperClass())) {
                throw incompatibleWrapper(objectHandle, wrapperClass, resolved.wrapperClass());
            }
            ObjectLease lease =
                    new ObjectLease(
                            contextHandle, objectHandle, effectiveOwnership, engine, this::isAlive);
            try {
                FoundryObject wrapper =
                        resolved == null
                                ? Objects.requireNonNull(
                                        factory.create(this, lease), "factory result")
                                : resolved.create(this, lease);
                if (!wrapperClass.isInstance(wrapper)) {
                    throw incompatibleWrapper(objectHandle, wrapperClass, wrapper.getClass());
                }
                wrappers.put(objectHandle, new WeakReference<>(wrapper));
                leases.add(lease);
                created = wrapperClass.cast(wrapper);
            } catch (Throwable failure) {
                failureTransition = lease.transitionToInvalid(true);
                factoryFailure = failure;
            }
        }
        if (failureTransition != null) {
            failureTransition.run();
            return rethrowUnchecked(factoryFailure);
        }
        return created;
    }

    public void invalidateObject(long objectHandle) {
        if (objectHandle == 0) {
            return;
        }
        List<ObjectLease.Transition> transitions = new ArrayList<>();
        synchronized (lifecycleLock) {
            invalidatedObjects.add(objectHandle);
            wrappers.remove(objectHandle);
            Iterator<ObjectLease> iterator = leases.iterator();
            while (iterator.hasNext()) {
                ObjectLease lease = iterator.next();
                if (lease.objectHandle() == objectHandle) {
                    transitions.add(lease.transitionToInvalid(true));
                    iterator.remove();
                }
            }
        }
        transitions.forEach(ObjectLease.Transition::run);
    }

    void releaseWrapper(ObjectLease lease) {
        Objects.requireNonNull(lease, "lease");
        synchronized (lifecycleLock) {
            WeakReference<FoundryObject> reference = wrappers.get(lease.objectHandle());
            FoundryObject wrapper = reference == null ? null : reference.get();
            if (wrapper != null && wrapper.lease() == lease) {
                wrappers.remove(lease.objectHandle());
            }
            leases.remove(lease);
        }
        lease.run();
    }

    @Override
    public void close() {
        callbackRegistry.disableAndDrain();
        List<ObjectLease.Transition> transitions = new ArrayList<>();
        synchronized (lifecycleLock) {
            if (!alive) {
                return;
            }
            alive = false;
            wrappers.clear();
            wrapperRegistrations.clear();
            foundryTypesByJavaName.clear();
            extensionFoundryTypesByJavaName = Map.of();
            for (ObjectLease lease : leases) {
                transitions.add(lease.transitionToInvalid(true));
            }
            leases.clear();
        }
        transitions.forEach(ObjectLease.Transition::run);
    }

    void closeCallbackAdmission() {
        callbackRegistry.closeAdmission();
    }

    boolean drainCallbacks() {
        return callbackRegistry.drain();
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

    private WrapperRegistration<?> resolveRegistration(long objectHandle) {
        String foundryType =
                Objects.requireNonNull(
                        engine.objectType(contextHandle, objectHandle), "Foundry object type");
        return foundryType.isBlank() ? null : wrapperRegistrations.get(foundryType);
    }

    private IllegalArgumentException incompatibleWrapper(
            long objectHandle,
            Class<? extends FoundryObject> requested,
            Class<? extends FoundryObject> actual) {
        return new IllegalArgumentException(
                "Foundry object "
                        + objectHandle
                        + " in context "
                        + contextHandle
                        + " is represented by "
                        + actual.getName()
                        + ", which is incompatible with requested "
                        + requested.getName()
                        + ".");
    }

    private static <T> T rethrowUnchecked(Throwable failure) {
        FoundryBindingContext.<RuntimeException>throwUnchecked(failure);
        throw new AssertionError("unreachable");
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }

    private record WrapperRegistration<T extends FoundryObject>(
            ObjectOwnership ownership, Class<T> wrapperClass, ObjectFactory<T> factory) {
        FoundryObject create(FoundryBindingContext context, ObjectLease lease) {
            return Objects.requireNonNull(factory.create(context, lease), "factory result");
        }
    }
}
