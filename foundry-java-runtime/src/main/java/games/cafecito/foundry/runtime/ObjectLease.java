package games.cafecito.foundry.runtime;

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
    private boolean retained;
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
        if (ownership == ObjectOwnership.REFERENCE_COUNTED) {
            engine.retain(contextHandle, objectHandle);
            retained = true;
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
            return retained ? ObjectOwnership.REFERENCE_COUNTED : ObjectOwnership.BORROWED;
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

    void invalidate() {
        synchronized (stateLock) {
            alive.set(false);
        }
    }

    void upgrade(ObjectOwnership requestedOwnership) {
        Objects.requireNonNull(requestedOwnership, "requestedOwnership");
        synchronized (stateLock) {
            if (requestedOwnership != ObjectOwnership.REFERENCE_COUNTED
                    || retained
                    || released
                    || !alive.get()) {
                return;
            }
            engine.retain(contextHandle, objectHandle);
            retained = true;
        }
    }

    @Override
    public void run() {
        boolean shouldRelease;
        synchronized (stateLock) {
            alive.set(false);
            shouldRelease = retained && !released;
            released = released || shouldRelease;
        }
        if (shouldRelease) {
            try {
                engine.release(contextHandle, objectHandle);
            } catch (Throwable failure) {
                try {
                    engine.reportCallbackException(contextHandle, 0, failure);
                } catch (Throwable ignored) {
                    // Cleaner and bridge shutdown boundaries must contain reporting failures too.
                }
            }
        }
    }
}
