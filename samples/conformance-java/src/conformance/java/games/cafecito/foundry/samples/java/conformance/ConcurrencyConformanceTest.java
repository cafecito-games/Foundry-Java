package games.cafecito.foundry.samples.java.conformance;

import static games.cafecito.foundry.samples.java.ConformanceCategory.DEINITIALIZATION_RACES;
import static games.cafecito.foundry.samples.java.ConformanceCategory.REENTRANT_CALLBACKS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import games.cafecito.foundry.runtime.FoundryBindingContext;
import games.cafecito.foundry.runtime.FoundryCallable;
import games.cafecito.foundry.runtime.FoundryInitializationLevel;
import games.cafecito.foundry.runtime.FoundryRegistryCoordinator;
import games.cafecito.foundry.samples.java.Covers;
import games.cafecito.foundry.samples.java.ScriptedEngine;
import games.cafecito.foundry.types.Variant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Concurrency conformance for reentrancy and deinitialization races.
 *
 * <p>Both hazards are exercised repeatedly rather than once, because a single pass through a race
 * proves nothing about flakiness. The iteration count is deliberately high and can be raised on a
 * device run with {@code -Dfoundry.conformance.iterations}.
 */
public class ConcurrencyConformanceTest {
    private static final long CONTEXT = ConformanceFixture.CONTEXT_HANDLE;
    private static final int ITERATIONS =
            Integer.getInteger("foundry.conformance.iterations", 200).intValue();

    @Test
    @Covers(REENTRANT_CALLBACKS)
    public void aSameThreadReentrantCallbackCompletesBothInvocationsInOrder() {
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            ScriptedEngine engine = new ScriptedEngine();
            FoundryRegistryCoordinator coordinator = initializedCoordinator(engine);
            FoundryBindingContext context = engine.bindingContext();
            AtomicLong innerHandle = new AtomicLong();
            AtomicInteger depth = new AtomicInteger();
            AtomicInteger maximumDepth = new AtomicInteger();
            FoundryCallable inner =
                    FoundryCallable.fixed(
                            0,
                            arguments -> {
                                maximumDepth.accumulateAndGet(depth.get(), Math::max);
                                return Variant.of(7L);
                            });
            FoundryCallable outer =
                    FoundryCallable.fixed(
                            0,
                            arguments -> {
                                depth.incrementAndGet();
                                long nested =
                                        coordinator.invoke(
                                                CONTEXT, innerHandle.get(), new long[] {});
                                depth.decrementAndGet();
                                long nestedValue =
                                        engine.decodeVariant(CONTEXT, nested).asLong();
                                return Variant.of(nestedValue + 1L);
                            });
            innerHandle.set(context.callbackRegistry().register(inner));
            long outerHandle = context.callbackRegistry().register(outer);

            long result = coordinator.invoke(CONTEXT, outerHandle, new long[] {});

            assertEquals(
                    "iteration " + iteration,
                    8L,
                    engine.decodeVariant(CONTEXT, result).asLong());
            assertEquals("iteration " + iteration, 1, maximumDepth.get());
            assertEquals("iteration " + iteration, List.of(), engine.reportedExceptions());
            coordinator.invalidate(CONTEXT);
            assertTrue("iteration " + iteration, coordinator.terminalCleanupComplete(CONTEXT));
        }
    }

    @Test
    @Covers(DEINITIALIZATION_RACES)
    public void aDeinitializationRacingACallbackAlwaysReachesOneCompleteTeardown()
            throws Exception {
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            ScriptedEngine engine = new ScriptedEngine();
            FoundryRegistryCoordinator coordinator = initializedCoordinator(engine);
            FoundryBindingContext context = engine.bindingContext();
            long callbackHandle =
                    context.callbackRegistry()
                            .register(FoundryCallable.fixed(0, arguments -> Variant.of(3L)));
            CyclicBarrier start = new CyclicBarrier(2);
            AtomicReference<Throwable> escaped = new AtomicReference<>();
            AtomicLong callbackResult = new AtomicLong(-1L);

            Thread caller =
                    new Thread(
                            () -> {
                                try {
                                    start.await(10, TimeUnit.SECONDS);
                                    callbackResult.set(
                                            coordinator.invoke(
                                                    CONTEXT, callbackHandle, new long[] {}));
                                } catch (Throwable failure) {
                                    escaped.compareAndSet(null, failure);
                                }
                            },
                            "foundry-conformance-caller-" + iteration);
            Thread teardown =
                    new Thread(
                            () -> {
                                try {
                                    start.await(10, TimeUnit.SECONDS);
                                    coordinator.deinitialize(
                                            CONTEXT, FoundryInitializationLevel.CORE.code());
                                } catch (Throwable failure) {
                                    escaped.compareAndSet(null, failure);
                                }
                            },
                            "foundry-conformance-teardown-" + iteration);
            caller.start();
            teardown.start();
            caller.join(TimeUnit.SECONDS.toMillis(30));
            teardown.join(TimeUnit.SECONDS.toMillis(30));

            String label = "iteration " + iteration;
            assertFalse(label, caller.isAlive());
            assertFalse(label, teardown.isAlive());
            assertEquals(label, null, escaped.get());
            // The callback either ran to completion before admission closed, or was refused with a
            // nil result. It is never partially observed and never throws across the boundary.
            long observed = callbackResult.get();
            assertTrue(
                    label + " unexpected callback result " + observed,
                    observed == 0L || engine.decodeVariant(CONTEXT, observed).asLong() == 3L);
            assertEquals(
                    label,
                    List.of(ConformanceFixture.SCENE_CLASS, ConformanceFixture.CORE_CLASS),
                    engine.unregistrations());
            assertTrue(label, coordinator.terminalCleanupComplete(CONTEXT));
            assertFalse(label, context.isAlive());
            assertEquals(label, List.of(), engine.reportedExceptions());
        }
    }

    private static FoundryRegistryCoordinator initializedCoordinator(ScriptedEngine engine) {
        FoundryRegistryCoordinator coordinator =
                new FoundryRegistryCoordinator(ConformanceFixture.bootstrap(), handle -> engine);
        for (FoundryInitializationLevel level :
                List.of(
                        FoundryInitializationLevel.CORE,
                        FoundryInitializationLevel.SERVERS,
                        FoundryInitializationLevel.SCENE)) {
            assertTrue(coordinator.initialize(CONTEXT, level.code()));
        }
        return coordinator;
    }
}
