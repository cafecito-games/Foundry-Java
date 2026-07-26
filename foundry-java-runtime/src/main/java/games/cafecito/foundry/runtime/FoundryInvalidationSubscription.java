package games.cafecito.foundry.runtime;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A removable subscription to an object lease's first invalidation.
 *
 * <p>Closing a subscription is idempotent. A subscription becomes inactive when it is removed or
 * when invalidation commits its listener for delivery.
 */
public final class FoundryInvalidationSubscription implements AutoCloseable {
    interface Controller {
        boolean isInvalidationSubscriptionActive(long subscriptionId);

        void removeInvalidationSubscription(long subscriptionId);
    }

    private final long subscriptionId;
    private final AtomicReference<Controller> controller;

    FoundryInvalidationSubscription(long subscriptionId, Controller controller) {
        this.subscriptionId = subscriptionId;
        this.controller = new AtomicReference<>(controller);
    }

    static FoundryInvalidationSubscription inactive() {
        return new FoundryInvalidationSubscription(0, null);
    }

    /**
     * Returns whether this listener remains eligible for a future invalidation snapshot.
     *
     * @return {@code true} only while the subscription can still be delivered
     */
    public boolean isActive() {
        Controller current = controller.get();
        if (current == null) {
            return false;
        }
        boolean active = current.isInvalidationSubscriptionActive(subscriptionId);
        if (!active) {
            controller.compareAndSet(current, null);
        }
        return active;
    }

    /** Removes this listener if invalidation has not already committed its delivery. */
    @Override
    public void close() {
        Controller current = controller.getAndSet(null);
        if (current != null) {
            current.removeInvalidationSubscription(subscriptionId);
        }
    }

    void deactivate() {
        controller.set(null);
    }
}
