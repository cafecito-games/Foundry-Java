package games.cafecito.foundry.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class ObjectConcurrencyTest {
    @Test
    void concurrentBindingPublishesOneRetainedWrapper() throws Exception {
        ObjectLifecycleTest.CountingEngine engine = new ObjectLifecycleTest.CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<ObjectLifecycleTest.TestObject>> futures = new ArrayList<>();
            for (int index = 0; index < 64; index++) {
                futures.add(
                        executor.submit(
                                () ->
                                        context.bind(
                                                7,
                                                ObjectOwnership.REFERENCE_COUNTED,
                                                ObjectLifecycleTest.TestObject.class,
                                                ObjectLifecycleTest.TestObject::new)));
            }
            ObjectLifecycleTest.TestObject expected = futures.get(0).get();
            for (Future<ObjectLifecycleTest.TestObject> future : futures) {
                assertSame(expected, future.get());
            }
            assertEquals(1, engine.retains.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @RepeatedTest(25)
    void closeInvalidateAndShutdownCannotDoubleReleaseOrRepublish() throws Exception {
        ObjectLifecycleTest.CountingEngine engine = new ObjectLifecycleTest.CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        ObjectLifecycleTest.TestObject object =
                context.bind(
                        7,
                        ObjectOwnership.REFERENCE_COUNTED,
                        ObjectLifecycleTest.TestObject.class,
                        ObjectLifecycleTest.TestObject::new);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> races =
                    List.of(
                            executor.submit(
                                    () -> {
                                        await(start);
                                        object.close();
                                    }),
                            executor.submit(
                                    () -> {
                                        await(start);
                                        context.invalidateObject(7);
                                    }),
                            executor.submit(
                                    () -> {
                                        await(start);
                                        context.close();
                                    }),
                            executor.submit(
                                    () -> {
                                        await(start);
                                        try {
                                            context.bind(
                                                    7,
                                                    ObjectOwnership.REFERENCE_COUNTED,
                                                    ObjectLifecycleTest.TestObject.class,
                                                    ObjectLifecycleTest.TestObject::new);
                                        } catch (FoundryObjectDisposedException expected) {
                                            // Shutdown won the race.
                                        }
                                    }));
            start.countDown();
            for (Future<?> race : races) {
                race.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertFalse(context.isAlive());
        assertFalse(object.isAlive());
        assertEquals(1, engine.retains.get());
        assertEquals(1, engine.releases.get());
        assertThrows(
                FoundryObjectDisposedException.class,
                () ->
                        context.bind(
                                7,
                                ObjectOwnership.REFERENCE_COUNTED,
                                ObjectLifecycleTest.TestObject.class,
                                ObjectLifecycleTest.TestObject::new));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
