package games.cafecito.foundry.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Idempotent lifetime state shared by an object wrapper and its Cleaner action.
 *
 * <p>This class carries opaque handles only. Native pointer storage and refcount transfer remain
 * bridge responsibilities.
 */
public final class ObjectLease implements Runnable {
    private final long contextHandle;
    private final long objectHandle;
    private final FoundryEngine engine;
    private final BooleanSupplier contextAlive;
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final Object stateLock = new Object();
    private final FoundryInvalidationSubscription.Controller invalidationController =
            new FoundryInvalidationSubscription.Controller() {
                @Override
                public boolean isInvalidationSubscriptionActive(long subscriptionId) {
                    return ObjectLease.this.isInvalidationSubscriptionActive(subscriptionId);
                }

                @Override
                public void removeInvalidationSubscription(long subscriptionId) {
                    ObjectLease.this.removeInvalidationSubscription(subscriptionId);
                }
            };
    private final LinkedHashMap<Long, InvalidationListener> invalidationListeners =
            new LinkedHashMap<>();
    private long nextInvalidationSubscriptionId = 1;
    private ObjectOwnership ownership;
    private boolean released;

    ObjectLease(
            long contextHandle,
            long objectHandle,
            ObjectOwnership ownership,
            FoundryEngine engine,
            BooleanSupplier contextAlive) {
        this.contextHandle = contextHandle;
        this.objectHandle = objectHandle;
        Objects.requireNonNull(ownership, "ownership");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.contextAlive = Objects.requireNonNull(contextAlive, "contextAlive");
        this.ownership = ownership;
        if (ownership == ObjectOwnership.REFERENCE_COUNTED) {
            engine.retain(contextHandle, objectHandle);
        }
    }

    public long contextHandle() {
        return contextHandle;
    }

    public long objectHandle() {
        return objectHandle;
    }

    public ObjectOwnership ownership() {
        synchronized (stateLock) {
            return ownership;
        }
    }

    public boolean isAlive() {
        if (!alive.get() || !contextAlive.getAsBoolean()) {
            return false;
        }
        if (!engine.isObjectValid(contextHandle, objectHandle)) {
            invalidate();
            return false;
        }
        return true;
    }

    public void requireAlive() {
        if (!isAlive()) {
            throw new FoundryObjectDisposedException(contextHandle, objectHandle);
        }
    }

    boolean isMarkedAlive() {
        return alive.get();
    }

    /**
     * Registers a listener for the first transition to an invalid lease.
     *
     * <p>If this lease is already invalid, the listener runs synchronously before this method
     * returns and the returned subscription is inactive.
     *
     * @param listener notification to run on the thread that detects invalidation
     * @return a removable invalidation subscription
     */
    public FoundryInvalidationSubscription onInvalidated(Runnable listener) {
        Runnable checkedListener = Objects.requireNonNull(listener, "listener");
        FoundryInvalidationSubscription subscription;
        synchronized (stateLock) {
            if (alive.get()) {
                long subscriptionId = nextInvalidationSubscriptionId++;
                subscription =
                        new FoundryInvalidationSubscription(subscriptionId, invalidationController);
                invalidationListeners.put(
                        subscriptionId, new InvalidationListener(checkedListener, subscription));
                return subscription;
            }
            subscription = FoundryInvalidationSubscription.inactive();
        }
        deliverListener(checkedListener);
        return subscription;
    }

    void invalidate() {
        transitionToInvalid(false).run();
    }

    void upgrade(ObjectOwnership requestedOwnership) {
        Objects.requireNonNull(requestedOwnership, "requestedOwnership");
        synchronized (stateLock) {
            if (requestedOwnership == ObjectOwnership.BORROWED
                    || ownership != ObjectOwnership.BORROWED
                    || released
                    || !alive.get()) {
                return;
            }
            if (requestedOwnership == ObjectOwnership.REFERENCE_COUNTED) {
                engine.retain(contextHandle, objectHandle);
            }
            ownership = requestedOwnership;
        }
    }

    @Override
    public void run() {
        transitionToInvalid(true).run();
    }

    Transition transitionToInvalid(boolean releaseReference) {
        List<Runnable> listeners;
        boolean shouldRelease;
        synchronized (stateLock) {
            alive.set(false);
            listeners = new ArrayList<>(invalidationListeners.size());
            for (InvalidationListener entry : invalidationListeners.values()) {
                entry.subscription().deactivate();
                listeners.add(entry.listener());
            }
            invalidationListeners.clear();
            shouldRelease = releaseReference && ownership != ObjectOwnership.BORROWED && !released;
            released = released || shouldRelease;
        }
        return new Transition(listeners, shouldRelease);
    }

    private boolean isInvalidationSubscriptionActive(long subscriptionId) {
        synchronized (stateLock) {
            return invalidationListeners.containsKey(subscriptionId);
        }
    }

    private void removeInvalidationSubscription(long subscriptionId) {
        synchronized (stateLock) {
            invalidationListeners.remove(subscriptionId);
        }
    }

    private void deliverListener(Runnable listener) {
        try {
            listener.run();
        } catch (Throwable failure) {
            reportLifecycleFailure(failure);
        }
    }

    private void reportLifecycleFailure(Throwable failure) {
        try {
            engine.reportCallbackException(contextHandle, 0, failure);
        } catch (Throwable ignored) {
            // Cleaner and bridge shutdown boundaries must contain reporting failures too.
        }
    }

    private record InvalidationListener(
            Runnable listener, FoundryInvalidationSubscription subscription) {}

    final class Transition implements Runnable {
        private final List<Runnable> listeners;
        private final boolean shouldRelease;
        private final AtomicBoolean executed = new AtomicBoolean();

        private Transition(List<Runnable> listeners, boolean shouldRelease) {
            this.listeners = List.copyOf(listeners);
            this.shouldRelease = shouldRelease;
        }

        @Override
        public void run() {
            if (!executed.compareAndSet(false, true)) {
                return;
            }
            for (Runnable listener : listeners) {
                deliverListener(listener);
            }
            releaseReference();
        }

        private void releaseReference() {
            if (!shouldRelease) {
                return;
            }
            try {
                engine.release(contextHandle, objectHandle);
            } catch (Throwable failure) {
                reportLifecycleFailure(failure);
            }
        }
    }
}
