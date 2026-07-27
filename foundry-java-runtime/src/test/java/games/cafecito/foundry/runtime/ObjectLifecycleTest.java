package games.cafecito.foundry.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ObjectLifecycleTest {
    @Test
    void objectAndLeaseSubscriptionsCanBeRemovedOrDeliveredExactlyOnce() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        TestObject object =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);
        AtomicInteger objectNotifications = new AtomicInteger();
        AtomicInteger leaseNotifications = new AtomicInteger();

        FoundryInvalidationSubscription objectSubscription =
                object.onInvalidated(objectNotifications::incrementAndGet);
        FoundryInvalidationSubscription leaseSubscription =
                object.lease().onInvalidated(leaseNotifications::incrementAndGet);

        assertTrue(objectSubscription.isActive());
        assertTrue(leaseSubscription.isActive());
        leaseSubscription.close();
        leaseSubscription.close();
        assertFalse(leaseSubscription.isActive());

        context.invalidateObject(7);
        context.invalidateObject(7);
        object.close();

        assertEquals(1, objectNotifications.get());
        assertEquals(0, leaseNotifications.get());
        assertFalse(objectSubscription.isActive());
    }

    @Test
    void wrapperCloseDeliversInvalidationOnce() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        TestObject object =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);
        AtomicInteger notifications = new AtomicInteger();
        FoundryInvalidationSubscription subscription =
                object.onInvalidated(notifications::incrementAndGet);

        object.close();
        object.close();

        assertEquals(1, notifications.get());
        assertFalse(subscription.isActive());
    }

    @Test
    void alreadyDeadRegistrationNotifiesSynchronouslyAndReturnsInactive() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        TestObject object =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);
        object.close();
        long subscribingThread = Thread.currentThread().getId();
        AtomicLong notificationThread = new AtomicLong();
        AtomicInteger notifications = new AtomicInteger();

        FoundryInvalidationSubscription subscription =
                object.onInvalidated(
                        () -> {
                            notificationThread.set(Thread.currentThread().getId());
                            notifications.incrementAndGet();
                        });

        assertEquals(1, notifications.get());
        assertEquals(subscribingThread, notificationThread.get());
        assertFalse(subscription.isActive());
    }

    @Test
    void invalidationSubscriptionRejectsNullListenerDeterministically() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        TestObject object =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);

        assertThrows(NullPointerException.class, () -> object.onInvalidated(null));
        object.close();
        assertThrows(NullPointerException.class, () -> object.onInvalidated(null));
    }

    @Test
    void contextCloseMarksStateDeadBeforeNotificationAndReleasesAfterward() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        TestObject object =
                context.bind(
                        7, ObjectOwnership.REFERENCE_COUNTED, TestObject.class, TestObject::new);
        AtomicInteger notifications = new AtomicInteger();
        engine.observedNotifications = notifications;
        object.onInvalidated(
                () -> {
                    assertFalse(context.isAlive());
                    assertFalse(object.isAlive());
                    notifications.incrementAndGet();
                });

        context.close();
        context.close();

        assertEquals(1, notifications.get());
        assertEquals(1, engine.releases.get());
        assertEquals(1, engine.notificationsObservedAtRelease);
    }

    @Test
    void objectInvalidationNotifiesBeforeReferenceRelease() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        TestObject object =
                context.bind(
                        7, ObjectOwnership.REFERENCE_COUNTED, TestObject.class, TestObject::new);
        AtomicInteger notifications = new AtomicInteger();
        engine.observedNotifications = notifications;
        object.onInvalidated(notifications::incrementAndGet);

        context.invalidateObject(7);

        assertEquals(1, notifications.get());
        assertEquals(1, engine.releases.get());
        assertEquals(1, engine.notificationsObservedAtRelease);
    }

    @Test
    void callbacksRunAfterLeaseAndContextLifecycleLocksAreReleased() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        TestObject object =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);
        AtomicInteger notifications = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            object.onInvalidated(
                    () -> {
                        Future<ObjectOwnership> leaseAccess =
                                executor.submit(object.lease()::ownership);
                        Future<Boolean> contextAccess =
                                executor.submit(
                                        () -> {
                                            assertThrows(
                                                    FoundryObjectDisposedException.class,
                                                    () ->
                                                            context.registerObjectType(
                                                                    "AfterClose",
                                                                    OtherObject.class,
                                                                    OtherObject::new));
                                            return true;
                                        });
                        assertEquals(
                                ObjectOwnership.BORROWED, awaitFuture(leaseAccess), "lease lock");
                        assertTrue(awaitFuture(contextAccess), "context lock");
                        notifications.incrementAndGet();
                    });

            context.close();

            assertEquals(1, notifications.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void factoryFailurePreservesOriginalAndCleansUpOutsideLifecycleLock() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        IllegalStateException factoryFailure = new IllegalStateException("factory failed");
        AtomicInteger notifications = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            IllegalStateException thrown =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    context.bind(
                                            7,
                                            ObjectOwnership.REFERENCE_COUNTED,
                                            TestObject.class,
                                            (bindingContext, lease) -> {
                                                lease.onInvalidated(
                                                        () -> {
                                                            Future<Boolean> contextAccess =
                                                                    executor.submit(
                                                                            () -> {
                                                                                bindingContext
                                                                                        .registerObjectType(
                                                                                                "AfterFactoryFailure",
                                                                                                OtherObject
                                                                                                        .class,
                                                                                                OtherObject
                                                                                                        ::new);
                                                                                return true;
                                                                            });
                                                            assertTrue(
                                                                    awaitFuture(contextAccess),
                                                                    "context lock");
                                                            notifications.incrementAndGet();
                                                        });
                                                throw factoryFailure;
                                            }));

            assertSame(factoryFailure, thrown);
            assertEquals(1, notifications.get());
            assertEquals(1, engine.releases.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void removalBeforeSnapshotPreventsDelivery() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        TestObject object =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);
        AtomicInteger notifications = new AtomicInteger();
        FoundryInvalidationSubscription subscription =
                object.onInvalidated(notifications::incrementAndGet);

        subscription.close();
        context.invalidateObject(7);

        assertEquals(0, notifications.get());
        assertFalse(subscription.isActive());
    }

    @Test
    void snapshotBeforeRemovalCommitsDelivery() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        TestObject object =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);
        CountDownLatch snapshotObserved = new CountDownLatch(1);
        CountDownLatch continueDelivery = new CountDownLatch(1);
        AtomicInteger committedNotifications = new AtomicInteger();
        object.onInvalidated(
                () -> {
                    snapshotObserved.countDown();
                    awaitLatch(continueDelivery);
                });
        FoundryInvalidationSubscription committed =
                object.onInvalidated(committedNotifications::incrementAndGet);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> invalidation = executor.submit(() -> context.invalidateObject(7));
            awaitLatch(snapshotObserved);

            committed.close();
            continueDelivery.countDown();
            awaitFuture(invalidation);

            assertEquals(1, committedNotifications.get());
            assertFalse(committed.isActive());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentRemovalAndInvalidationNeverDeliverMoreThanOnce() throws Exception {
        CountingEngine engine = new CountingEngine();
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (long handle = 1; handle <= 64; handle++) {
                engine.valid(handle);
                TestObject object =
                        context.bind(
                                handle,
                                ObjectOwnership.BORROWED,
                                TestObject.class,
                                TestObject::new);
                AtomicInteger notifications = new AtomicInteger();
                FoundryInvalidationSubscription subscription =
                        object.onInvalidated(notifications::incrementAndGet);
                CyclicBarrier start = new CyclicBarrier(3);
                Future<?> removal =
                        executor.submit(
                                () -> {
                                    awaitBarrier(start);
                                    subscription.close();
                                });
                long currentHandle = handle;
                Future<?> invalidation =
                        executor.submit(
                                () -> {
                                    awaitBarrier(start);
                                    context.invalidateObject(currentHandle);
                                });

                awaitBarrier(start);
                awaitFuture(removal);
                awaitFuture(invalidation);

                assertTrue(notifications.get() == 0 || notifications.get() == 1);
                assertFalse(subscription.isActive());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentLeaseInvalidationAndContextCloseDeliverExactlyOnce() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        TestObject object =
                context.bind(
                        7, ObjectOwnership.REFERENCE_COUNTED, TestObject.class, TestObject::new);
        AtomicInteger notifications = new AtomicInteger();
        object.onInvalidated(notifications::incrementAndGet);
        CyclicBarrier start = new CyclicBarrier(3);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> leaseInvalidation =
                    executor.submit(
                            () -> {
                                awaitBarrier(start);
                                object.lease().invalidate();
                            });
            Future<?> contextClose =
                    executor.submit(
                            () -> {
                                awaitBarrier(start);
                                context.close();
                            });

            awaitBarrier(start);
            awaitFuture(leaseInvalidation);
            awaitFuture(contextClose);

            assertEquals(1, notifications.get());
            assertEquals(1, engine.releases.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void wrapperCloseDeliversOnTheClosingThread() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        TestObject object =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);
        AtomicLong notificationThread = new AtomicLong();
        object.onInvalidated(() -> notificationThread.set(Thread.currentThread().getId()));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Long> closeThread =
                    executor.submit(
                            () -> {
                                object.close();
                                return Thread.currentThread().getId();
                            });

            assertEquals(awaitFuture(closeThread).longValue(), notificationThread.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invalidationListenersMayReenterSubscriptionAndClosePaths() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        TestObject object =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);
        AtomicInteger primaryNotifications = new AtomicInteger();
        AtomicInteger committedNotifications = new AtomicInteger();
        AtomicInteger immediateNotifications = new AtomicInteger();
        AtomicReference<FoundryInvalidationSubscription> primary = new AtomicReference<>();
        AtomicReference<FoundryInvalidationSubscription> committed = new AtomicReference<>();
        primary.set(
                object.onInvalidated(
                        () -> {
                            assertFalse(primary.get().isActive());
                            committed.get().close();
                            FoundryInvalidationSubscription immediate =
                                    object.onInvalidated(immediateNotifications::incrementAndGet);
                            assertFalse(immediate.isActive());
                            object.close();
                            primaryNotifications.incrementAndGet();
                        }));
        committed.set(object.onInvalidated(committedNotifications::incrementAndGet));

        context.invalidateObject(7);

        assertEquals(1, primaryNotifications.get());
        assertEquals(1, committedNotifications.get());
        assertEquals(1, immediateNotifications.get());
    }

    @Test
    void listenerFailuresAreContainedReportedAndDoNotStopDeliveryOrRelease() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        TestObject object =
                context.bind(
                        7, ObjectOwnership.REFERENCE_COUNTED, TestObject.class, TestObject::new);
        AssertionError listenerFailure = new AssertionError("listener failed");
        AtomicInteger laterNotifications = new AtomicInteger();
        object.onInvalidated(
                () -> {
                    throw listenerFailure;
                });
        object.onInvalidated(laterNotifications::incrementAndGet);

        context.invalidateObject(7);

        assertSame(listenerFailure, engine.reportedFailure);
        assertEquals(0, engine.reportedCallbackHandle);
        assertEquals(1, laterNotifications.get());
        assertEquals(1, engine.releases.get());
    }

    @Test
    void alreadyDeadListenerAndReporterFailuresAreContained() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        TestObject object =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);
        object.close();
        AssertionError listenerFailure = new AssertionError("immediate listener failed");

        FoundryInvalidationSubscription reported =
                assertDoesNotThrow(
                        () ->
                                object.onInvalidated(
                                        () -> {
                                            throw listenerFailure;
                                        }));
        assertFalse(reported.isActive());
        assertSame(listenerFailure, engine.reportedFailure);
        assertEquals(0, engine.reportedCallbackHandle);

        engine.throwOnReport = true;
        FoundryInvalidationSubscription contained =
                assertDoesNotThrow(
                        () ->
                                object.onInvalidated(
                                        () -> {
                                            throw new AssertionError("reporter also fails");
                                        }));
        assertFalse(contained.isActive());
    }

    @Test
    void returnsOneCanonicalWrapperPerContextHandleAndRejectsClassMismatches() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);

        TestObject first =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);
        TestObject second =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);
        FoundryBindingContext secondContext = new FoundryBindingContext(12, engine);
        TestObject otherContext =
                secondContext.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);

        assertSame(first, second);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        context.bind(
                                7, ObjectOwnership.BORROWED, OtherObject.class, OtherObject::new));
        org.junit.jupiter.api.Assertions.assertNotSame(first, otherContext);
        assertEquals(7, first.objectHandle());
        assertEquals(11, first.context().contextHandle());
    }

    @Test
    void cacheHitUpgradesBorrowedOwnershipExactlyOnceAndNeverDowngrades() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);

        TestObject borrowed =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);
        TestObject retained =
                context.bind(
                        7, ObjectOwnership.REFERENCE_COUNTED, TestObject.class, TestObject::new);
        TestObject borrowedAgain =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);

        assertSame(borrowed, retained);
        assertSame(retained, borrowedAgain);
        assertEquals(1, engine.retains.get());

        borrowed.close();
        retained.close();
        assertEquals(1, engine.releases.get());
    }

    @Test
    void referenceCountedFirstNeverRetainsAgainForBorrowedAliases() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);

        TestObject retained =
                context.bind(
                        7, ObjectOwnership.REFERENCE_COUNTED, TestObject.class, TestObject::new);
        TestObject borrowed =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);

        assertSame(retained, borrowed);
        assertEquals(1, engine.retains.get());
        context.invalidateObject(7);
        assertEquals(1, engine.releases.get());
    }

    @Test
    void firstBaseRequestResolvesAndPublishesTheMostDerivedRegisteredWrapper() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        engine.nativeTypes.put(7L, "DerivedObject");
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        context.registerObjectType("TestObject", TestObject.class, TestObject::new);
        context.registerObjectType("DerivedObject", DerivedObject.class, DerivedObject::new);

        TestObject throughBase =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);
        DerivedObject throughDerived =
                context.bind(7, ObjectOwnership.BORROWED, DerivedObject.class, DerivedObject::new);

        assertTrue(throughBase instanceof DerivedObject);
        assertSame(throughBase, throughDerived);
    }

    @Test
    void registeredRefCountedTypeRetainsBeforePublishingBorrowedDecode() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        engine.nativeTypes.put(7L, "TestObject");
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        context.registerObjectType(
                "TestObject", ObjectOwnership.REFERENCE_COUNTED, TestObject.class, TestObject::new);

        TestObject decoded =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);

        assertEquals(1, engine.retains.get());
        assertEquals(ObjectOwnership.REFERENCE_COUNTED, decoded.lease().ownership());
        decoded.close();
        assertEquals(1, engine.releases.get());
    }

    @Test
    void rejectsNullOrInvalidObjectHandlesBeforePublication() {
        CountingEngine engine = new CountingEngine();
        FoundryBindingContext context = new FoundryBindingContext(11, engine);

        assertThrows(
                IllegalArgumentException.class,
                () -> context.bind(0, ObjectOwnership.BORROWED, TestObject.class, TestObject::new));
        assertThrows(
                FoundryObjectDisposedException.class,
                () -> context.bind(9, ObjectOwnership.BORROWED, TestObject.class, TestObject::new));
    }

    @Test
    void borrowedInvalidationNeverReleasesNativeOwnership() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        TestObject object =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);

        context.invalidateObject(7);

        assertFalse(object.isAlive());
        assertThrows(FoundryObjectDisposedException.class, object::objectHandle);
        assertEquals(0, engine.releases.get());
    }

    @Test
    void engineValidityProbeDeliversInvalidationOnceWithoutReleasingBorrowedOwnership() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        TestObject object =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);
        AtomicInteger notifications = new AtomicInteger();
        FoundryInvalidationSubscription subscription =
                object.onInvalidated(notifications::incrementAndGet);
        engine.valid.put(7L, false);

        assertFalse(object.isAlive());
        assertThrows(FoundryObjectDisposedException.class, object::objectHandle);
        assertFalse(object.isAlive());

        assertEquals(1, notifications.get());
        assertFalse(subscription.isActive());
        assertEquals(0, engine.releases.get());
    }

    @Test
    void referenceCountedObjectsRetainAndReleaseExactlyOnce() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        TestObject first =
                context.bind(
                        7, ObjectOwnership.REFERENCE_COUNTED, TestObject.class, TestObject::new);
        TestObject alias =
                context.bind(
                        7, ObjectOwnership.REFERENCE_COUNTED, TestObject.class, TestObject::new);

        first.close();
        alias.close();
        first.runCleanerForTesting();

        assertEquals(1, engine.retains.get());
        assertEquals(1, engine.releases.get());
        assertFalse(first.isAlive());
        assertThrows(FoundryObjectDisposedException.class, first::objectHandle);
    }

    @Test
    void closeReleasesOnlyTheWrapperAndAllowsAStillLiveObjectToBeReacquired() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);

        TestObject first =
                context.bind(
                        7, ObjectOwnership.REFERENCE_COUNTED, TestObject.class, TestObject::new);
        first.close();
        TestObject reacquired =
                context.bind(
                        7, ObjectOwnership.REFERENCE_COUNTED, TestObject.class, TestObject::new);

        org.junit.jupiter.api.Assertions.assertNotSame(first, reacquired);
        assertFalse(first.isAlive());
        assertTrue(reacquired.isAlive());
        assertEquals(2, engine.retains.get());
        assertEquals(1, engine.releases.get());

        reacquired.close();
        assertEquals(2, engine.releases.get());
    }

    @Test
    void closingABorrowedWrapperDoesNotTombstoneTheEngineObject() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);

        TestObject first =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);
        first.close();
        TestObject reacquired =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);

        org.junit.jupiter.api.Assertions.assertNotSame(first, reacquired);
        assertFalse(first.isAlive());
        assertTrue(reacquired.isAlive());
        assertEquals(0, engine.retains.get());
        assertEquals(0, engine.releases.get());
    }

    @Test
    void shutdownInvalidatesBeforeReleasingAndRejectsNewWrappers() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        engine.observedContext = context;
        TestObject borrowed =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);
        engine.valid(8);
        TestObject retained =
                context.bind(
                        8, ObjectOwnership.REFERENCE_COUNTED, TestObject.class, TestObject::new);

        context.close();

        assertFalse(context.isAlive());
        assertFalse(borrowed.isAlive());
        assertFalse(retained.isAlive());
        assertEquals(1, engine.releases.get());
        assertTrue(engine.releasedAfterContextInvalidation);
        assertThrows(
                FoundryObjectDisposedException.class,
                () -> context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new));
    }

    private static <T> T awaitFuture(Future<T> future) {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("concurrent lifecycle access did not complete", failure);
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS), "latch did not complete");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(2, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("race barrier did not complete", failure);
        }
    }

    static class TestObject extends FoundryObject {
        TestObject(FoundryBindingContext context, ObjectLease lease) {
            super(context, lease);
        }
    }

    static final class DerivedObject extends TestObject {
        DerivedObject(FoundryBindingContext context, ObjectLease lease) {
            super(context, lease);
        }
    }

    static final class OtherObject extends FoundryObject {
        OtherObject(FoundryBindingContext context, ObjectLease lease) {
            super(context, lease);
        }
    }

    static final class CountingEngine extends NoOpEngine {
        final ConcurrentHashMap<Long, Boolean> valid = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Long, String> nativeTypes = new ConcurrentHashMap<>();
        final AtomicInteger retains = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();
        volatile FoundryBindingContext observedContext;
        volatile boolean releasedAfterContextInvalidation;
        volatile AtomicInteger observedNotifications;
        volatile int notificationsObservedAtRelease;
        volatile Throwable reportedFailure;
        volatile long reportedCallbackHandle = -1;
        volatile boolean throwOnReport;

        void valid(long handle) {
            valid.put(handle, true);
        }

        @Override
        public boolean isObjectValid(long contextHandle, long objectHandle) {
            return valid.getOrDefault(objectHandle, false);
        }

        @Override
        public String objectType(long contextHandle, long objectHandle) {
            return nativeTypes.getOrDefault(objectHandle, "");
        }

        @Override
        public void retain(long contextHandle, long objectHandle) {
            retains.incrementAndGet();
        }

        @Override
        public void release(long contextHandle, long objectHandle) {
            AtomicInteger notifications = observedNotifications;
            notificationsObservedAtRelease = notifications == null ? 0 : notifications.get();
            releases.incrementAndGet();
            FoundryBindingContext context = observedContext;
            releasedAfterContextInvalidation = context == null || !context.isAlive();
        }

        @Override
        public void reportCallbackException(
                long contextHandle, long callbackHandle, Throwable failure) {
            reportedCallbackHandle = callbackHandle;
            reportedFailure = failure;
            if (throwOnReport) {
                throw new IllegalStateException("reporting failed");
            }
        }
    }
}
