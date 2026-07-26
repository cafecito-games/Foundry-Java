package games.cafecito.foundry.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.cafecito.foundry.types.Variant;
import games.cafecito.foundry.types.VariantCodec;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CallbackShutdownTest {
    @Test
    void bridgeCallbacksDecodeInvokeAndEncodeOnTheCallingThread() {
        CallbackEngine engine = new CallbackEngine();
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        FoundryRuntimeCallbacks callbacks = new FoundryRuntimeCallbacks();
        callbacks.register(context);
        long callingThread = Thread.currentThread().getId();
        AtomicLong observedThread = new AtomicLong();
        long callback =
                context.callbackRegistry()
                        .register(
                                FoundryCallable.unary(
                                        VariantCodec.STRING,
                                        VariantCodec.STRING,
                                        value -> {
                                            observedThread.set(Thread.currentThread().getId());
                                            return value.toUpperCase();
                                        }));
        engine.variants.put(21L, Variant.of("player"));

        assertTrue(callbacks.initialize(11, 2));
        long resultHandle = callbacks.invoke(11, callback, new long[] {21});

        assertEquals(Variant.of("PLAYER"), engine.variants.get(resultHandle));
        assertEquals(callingThread, observedThread.get());
    }

    @Test
    void onlyFinalCoreDeinitializationShutsDownTheContext() {
        CallbackEngine engine = new CallbackEngine();
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        FoundryRuntimeCallbacks callbacks = new FoundryRuntimeCallbacks();
        callbacks.register(context);

        assertTrue(callbacks.initialize(11, FoundryInitializationLevel.CORE.code()));
        assertTrue(callbacks.initialize(11, FoundryInitializationLevel.SERVERS.code()));
        assertTrue(callbacks.initialize(11, FoundryInitializationLevel.SCENE.code()));
        assertTrue(callbacks.initialize(11, FoundryInitializationLevel.EDITOR.code()));
        callbacks.deinitialize(11, FoundryInitializationLevel.EDITOR.code());
        callbacks.deinitialize(11, FoundryInitializationLevel.SCENE.code());
        callbacks.deinitialize(11, FoundryInitializationLevel.SERVERS.code());

        assertTrue(context.isAlive());
        assertTrue(context.callbackRegistry().isEnabled());

        callbacks.deinitialize(11, FoundryInitializationLevel.CORE.code());
        assertFalse(context.isAlive());
        assertFalse(context.callbackRegistry().isEnabled());
    }

    @Test
    void callbacksSupportSameThreadReentrancy() {
        CallbackEngine engine = new CallbackEngine();
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        FoundryRuntimeCallbacks callbacks = new FoundryRuntimeCallbacks();
        callbacks.register(context);
        long inner =
                context.callbackRegistry()
                        .register(FoundryCallable.variadic(ignored -> Variant.of("inner")));
        long outer =
                context.callbackRegistry()
                        .register(
                                FoundryCallable.variadic(
                                        ignored ->
                                                context.callbackRegistry()
                                                        .invoke(inner, List.of())));

        long result = callbacks.invoke(11, outer, new long[0]);

        assertEquals(Variant.of("inner"), engine.variants.get(result));
    }

    @Test
    void containsEveryThrowableAtTheBridgeBoundary() {
        CallbackEngine engine = new CallbackEngine();
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        FoundryRuntimeCallbacks callbacks = new FoundryRuntimeCallbacks();
        callbacks.register(context);
        Error failure = new AssertionError("boom");
        long callback =
                context.callbackRegistry()
                        .register(
                                FoundryCallable.variadic(
                                        ignored -> {
                                            throw failure;
                                        }));

        long result = callbacks.invoke(11, callback, new long[0]);

        assertEquals(0, result);
        assertEquals(failure, engine.reportedFailure);
        assertEquals(callback, engine.reportedCallback);
    }

    @Test
    void shutdownDisablesLateCallbacksBeforeObjectRelease() throws Exception {
        CallbackEngine engine = new CallbackEngine();
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        engine.context = context;
        FoundryRuntimeCallbacks callbacks = new FoundryRuntimeCallbacks();
        callbacks.register(context);
        engine.valid = true;
        context.bind(
                7,
                ObjectOwnership.REFERENCE_COUNTED,
                CallAndSignalTest.CallableObject.class,
                CallAndSignalTest.CallableObject::new);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        long callback =
                context.callbackRegistry()
                        .register(
                                FoundryCallable.variadic(
                                        ignored -> {
                                            entered.countDown();
                                            await(finish);
                                            return Variant.of("done");
                                        }));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> inFlight =
                    executor.submit(() -> callbacks.invoke(11, callback, new long[0]));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            Future<?> shutdown = executor.submit(() -> callbacks.deinitialize(11, 0));
            finish.countDown();
            assertTrue(inFlight.get(10, TimeUnit.SECONDS) != 0);
            shutdown.get(10, TimeUnit.SECONDS);

            assertEquals(0, callbacks.invoke(11, callback, new long[0]));
            assertFalse(context.isAlive());
            assertTrue(engine.releaseObservedCallbacksDisabled);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void deinitializeIsSafeWhenReenteredFromTheCallingThread() throws Exception {
        CallbackEngine engine = new CallbackEngine();
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        FoundryRuntimeCallbacks callbacks = new FoundryRuntimeCallbacks();
        callbacks.register(context);
        long[] callbackHandle = new long[1];
        callbackHandle[0] =
                context.callbackRegistry()
                        .register(
                                FoundryCallable.variadic(
                                        ignored -> {
                                            callbacks.deinitialize(11, 0);
                                            return Variant.of("must not encode");
                                        }));
        AtomicLong result = new AtomicLong(-1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread invocation =
                new Thread(
                        () -> {
                            try {
                                result.set(callbacks.invoke(11, callbackHandle[0], new long[0]));
                            } catch (Throwable thrown) {
                                failure.set(thrown);
                            }
                        },
                        "reentrant-deinitialize-test");
        invocation.setDaemon(true);

        invocation.start();
        invocation.join(TimeUnit.SECONDS.toMillis(2));

        assertFalse(invocation.isAlive(), "same-thread deinitialize deadlocked");
        assertEquals(null, failure.get());
        assertEquals(0, result.get());
        assertFalse(context.isAlive());
    }

    @Test
    void deinitializeContainsReleaseFailuresAtTheBridgeBoundary() {
        CallbackEngine engine = new CallbackEngine();
        engine.valid = true;
        engine.throwOnRelease = true;
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        FoundryRuntimeCallbacks callbacks = new FoundryRuntimeCallbacks();
        callbacks.register(context);
        context.bind(
                7,
                ObjectOwnership.REFERENCE_COUNTED,
                CallAndSignalTest.CallableObject.class,
                CallAndSignalTest.CallableObject::new);

        assertDoesNotThrow(() -> callbacks.deinitialize(11, 0));
        assertTrue(engine.reportedFailure instanceof IllegalStateException);
    }

    @Test
    void deinitializeNotifiesObjectInvalidationBeforeReleaseAndContainsLateRegistration() {
        CallbackEngine engine = new CallbackEngine();
        engine.valid = true;
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        FoundryRuntimeCallbacks callbacks = new FoundryRuntimeCallbacks();
        callbacks.register(context);
        CallAndSignalTest.CallableObject object =
                context.bind(
                        7,
                        ObjectOwnership.REFERENCE_COUNTED,
                        CallAndSignalTest.CallableObject.class,
                        CallAndSignalTest.CallableObject::new);
        AtomicInteger notifications = new AtomicInteger();
        engine.observedNotifications = notifications;
        object.onInvalidated(notifications::incrementAndGet);

        callbacks.deinitialize(11, FoundryInitializationLevel.CORE.code());

        assertEquals(1, notifications.get());
        assertEquals(1, engine.notificationsObservedAtRelease);
        AtomicInteger lateNotifications = new AtomicInteger();
        FoundryInvalidationSubscription late =
                object.onInvalidated(lateNotifications::incrementAndGet);
        assertEquals(1, lateNotifications.get());
        assertFalse(late.isActive());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    static final class CallbackEngine extends NoOpEngine {
        final Map<Long, Variant> variants = new ConcurrentHashMap<>();
        final AtomicLong nextVariant = new AtomicLong(100);
        volatile Throwable reportedFailure;
        volatile long reportedCallback;
        volatile boolean valid;
        volatile FoundryBindingContext context;
        volatile boolean releaseObservedCallbacksDisabled;
        volatile boolean throwOnRelease;
        volatile AtomicInteger observedNotifications;
        volatile int notificationsObservedAtRelease;

        @Override
        public Variant decodeVariant(long contextHandle, long variantHandle) {
            return variants.get(variantHandle);
        }

        @Override
        public long encodeVariant(long contextHandle, Variant value) {
            long handle = nextVariant.incrementAndGet();
            variants.put(handle, value);
            return handle;
        }

        @Override
        public boolean isObjectValid(long contextHandle, long objectHandle) {
            return valid;
        }

        @Override
        public void reportCallbackException(
                long contextHandle, long callbackHandle, Throwable failure) {
            reportedCallback = callbackHandle;
            reportedFailure = failure;
        }

        @Override
        public void release(long contextHandle, long objectHandle) {
            AtomicInteger notifications = observedNotifications;
            notificationsObservedAtRelease = notifications == null ? 0 : notifications.get();
            FoundryBindingContext current = context;
            releaseObservedCallbacksDisabled =
                    current == null || !current.callbackRegistry().isEnabled();
            if (throwOnRelease) {
                throw new IllegalStateException("release failed");
            }
        }
    }
}
