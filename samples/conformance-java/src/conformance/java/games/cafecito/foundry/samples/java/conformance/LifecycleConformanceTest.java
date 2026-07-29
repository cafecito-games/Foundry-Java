package games.cafecito.foundry.samples.java.conformance;

import static games.cafecito.foundry.samples.java.ConformanceCategory.CLOSE_AND_CLEANER_FALLBACK;
import static games.cafecito.foundry.samples.java.ConformanceCategory.DEINITIALIZATION;
import static games.cafecito.foundry.samples.java.ConformanceCategory.EXCEPTIONS_FROM_CALLBACKS;
import static games.cafecito.foundry.samples.java.ConformanceCategory.INITIALIZATION;
import static games.cafecito.foundry.samples.java.ConformanceCategory.INITIALIZATION_LEVEL_MISMATCH;
import static games.cafecito.foundry.samples.java.ConformanceCategory.OBJECT_DESTRUCTION;
import static games.cafecito.foundry.samples.java.ConformanceCategory.THREAD_ATTACH_AND_DETACH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import games.cafecito.foundry.generated.classes.Node;
import games.cafecito.foundry.runtime.FoundryBindingContext;
import games.cafecito.foundry.runtime.FoundryCallable;
import games.cafecito.foundry.runtime.FoundryInitializationLevel;
import games.cafecito.foundry.runtime.FoundryInvalidationSubscription;
import games.cafecito.foundry.runtime.FoundryObjectDisposedException;
import games.cafecito.foundry.runtime.FoundryRegistryCoordinator;
import games.cafecito.foundry.samples.java.ConformanceSpinner;
import games.cafecito.foundry.samples.java.Covers;
import games.cafecito.foundry.samples.java.ScriptedEngine;
import games.cafecito.foundry.types.StringName;
import games.cafecito.foundry.types.Variant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;

/** Behavioural conformance for the registry lifecycle and its documented hazards. */
public class LifecycleConformanceTest {
    private static final long CONTEXT = ConformanceFixture.CONTEXT_HANDLE;
    private static final String GET_NAME = "classes/Node/methods/get_name";

    private ScriptedEngine engine;

    @Before
    public void createEngine() {
        engine = new ScriptedEngine();
    }

    @Test
    @Covers(INITIALIZATION)
    public void eachInitializationLevelRegistersOnlyItsOwnDeclaredClasses() {
        FoundryRegistryCoordinator coordinator = coordinator();

        assertTrue(coordinator.initialize(CONTEXT, FoundryInitializationLevel.CORE.code()));
        assertEquals(List.of(ConformanceFixture.CORE_CLASS), engine.registrations());
        assertTrue(coordinator.initialize(CONTEXT, FoundryInitializationLevel.SERVERS.code()));
        assertEquals(List.of(ConformanceFixture.CORE_CLASS), engine.registrations());
        assertTrue(coordinator.initialize(CONTEXT, FoundryInitializationLevel.SCENE.code()));

        assertEquals(
                List.of(ConformanceFixture.CORE_CLASS, ConformanceFixture.SCENE_CLASS),
                engine.registrations());
        assertEquals(List.of(), engine.unregistrations());
    }

    @Test
    @Covers(DEINITIALIZATION)
    public void deinitializationUnregistersInExactReverseOrderAndCompletesTheContext() {
        FoundryRegistryCoordinator coordinator = initializedCoordinator();

        coordinator.deinitialize(CONTEXT, FoundryInitializationLevel.SCENE.code());
        coordinator.deinitialize(CONTEXT, FoundryInitializationLevel.SERVERS.code());
        coordinator.deinitialize(CONTEXT, FoundryInitializationLevel.CORE.code());

        assertEquals(
                List.of(ConformanceFixture.SCENE_CLASS, ConformanceFixture.CORE_CLASS),
                engine.unregistrations());
        assertTrue(coordinator.terminalCleanupComplete(CONTEXT));
        assertFalse(engine.bindingContext().isAlive());
        assertEquals(List.of(), engine.reportedExceptions());
    }

    @Test
    @Covers(INITIALIZATION_LEVEL_MISMATCH)
    public void anOutOfOrderInitializationLevelIsRejectedWithoutRegisteringAnything() {
        FoundryRegistryCoordinator skipCore = coordinator();
        ScriptedEngine skipServersEngine = new ScriptedEngine();
        FoundryRegistryCoordinator skipServers =
                new FoundryRegistryCoordinator(
                        ConformanceFixture.bootstrap(), handle -> skipServersEngine);

        assertFalse(skipCore.initialize(CONTEXT, FoundryInitializationLevel.SCENE.code()));
        assertTrue(skipServers.initialize(CONTEXT, FoundryInitializationLevel.CORE.code()));
        assertFalse(skipServers.initialize(CONTEXT, FoundryInitializationLevel.SCENE.code()));
        assertFalse(skipServers.initialize(CONTEXT, 99));
        assertFalse(skipCore.initialize(0L, FoundryInitializationLevel.CORE.code()));

        assertEquals(List.of(), engine.registrations());
        assertEquals(List.of(ConformanceFixture.CORE_CLASS), skipServersEngine.registrations());
        assertEquals(List.of(), skipServersEngine.unregistrations());
    }

    @Test
    @Covers(OBJECT_DESTRUCTION)
    public void engineSideDestructionInvalidatesTheWrapperBeforeTheNextCall() {
        FoundryBindingContext context = new FoundryBindingContext(CONTEXT, engine);
        long handle = engine.declareObject("Node");
        engine.respondWith(GET_NAME, Variant.of(new StringName("before")));
        Node node = Node.bind(context, handle);
        AtomicInteger invalidations = new AtomicInteger();
        FoundryInvalidationSubscription subscription =
                node.onInvalidated(invalidations::incrementAndGet);
        assertEquals(new StringName("before"), node.getName());

        engine.destroyObject(handle);

        assertFalse(node.isAlive());
        assertFalse(subscription.isActive());
        assertEquals(1, invalidations.get());
        FoundryObjectDisposedException disposed =
                assertThrows(FoundryObjectDisposedException.class, node::getName);
        assertTrue(disposed.getMessage().contains(Long.toString(handle)));
        assertEquals(1, engine.callsTo(GET_NAME).size());
        context.close();
    }

    @Test
    @Covers(CLOSE_AND_CLEANER_FALLBACK)
    public void closeReleasesExactlyOnceAndAnUnreachableWrapperStillReleases() throws Exception {
        FoundryBindingContext context = new FoundryBindingContext(CONTEXT, engine);
        long closedHandle = engine.declareObject(ConformanceFixture.SCENE_CLASS);
        long abandonedHandle = engine.declareObject(ConformanceFixture.SCENE_CLASS);
        ConformanceSpinner closed = referenceCounted(context, closedHandle);
        ConformanceSpinner abandoned = referenceCounted(context, abandonedHandle);

        closed.close();
        closed.close();
        abandoned = null;
        awaitCleanerRelease(abandonedHandle);

        assertEquals(1L, engine.retainCount(closedHandle));
        assertEquals(1L, engine.releaseCount(closedHandle));
        assertEquals(1L, engine.retainCount(abandonedHandle));
        assertEquals(1L, engine.releaseCount(abandonedHandle));
        assertEquals(List.of(), engine.reportedExceptions());
        context.close();
        assertEquals(1L, engine.releaseCount(closedHandle));
        assertEquals(1L, engine.releaseCount(abandonedHandle));
    }

    @Test
    @Covers(THREAD_ATTACH_AND_DETACH)
    public void aCallbackRunsOnAnyAttachedThreadAndIsRefusedAfterDetach() throws Exception {
        FoundryRegistryCoordinator coordinator = initializedCoordinator();
        FoundryBindingContext context = engine.bindingContext();
        long callbackHandle =
                context.callbackRegistry()
                        .register(
                                FoundryCallable.fixed(
                                        1,
                                        arguments ->
                                                Variant.of(arguments.get(0).asLong() * 2L)));
        long argumentHandle = engine.encodeVariant(CONTEXT, Variant.of(21L));
        BlockingQueue<Long> results = new ArrayBlockingQueue<>(1);

        Thread attached =
                new Thread(
                        () ->
                                results.add(
                                        coordinator.invoke(
                                                CONTEXT,
                                                callbackHandle,
                                                new long[] {argumentHandle})),
                        "foundry-conformance-attached");
        attached.start();
        attached.join(TimeUnit.SECONDS.toMillis(10));
        Long resultHandle = results.poll(10, TimeUnit.SECONDS);

        assertEquals(
                42L, engine.decodeVariant(CONTEXT, resultHandle.longValue()).asLong());
        coordinator.invalidate(CONTEXT);
        assertEquals(
                0L,
                coordinator.invoke(CONTEXT, callbackHandle, new long[] {argumentHandle}));
        assertFalse(context.isAlive());
    }

    @Test
    @Covers(EXCEPTIONS_FROM_CALLBACKS)
    public void aThrowingCallbackIsReportedToTheBridgeAndReturnsNilInsteadOfPropagating() {
        FoundryRegistryCoordinator coordinator = initializedCoordinator();
        FoundryBindingContext context = engine.bindingContext();
        IllegalStateException thrown = new IllegalStateException("sample callback failure");
        long callbackHandle =
                context.callbackRegistry()
                        .register(
                                FoundryCallable.fixed(
                                        0,
                                        arguments -> {
                                            throw thrown;
                                        }));

        long result = coordinator.invoke(CONTEXT, callbackHandle, new long[] {});

        assertEquals(0L, result);
        assertEquals(List.of(thrown), engine.reportedExceptions());
        assertTrue(context.isAlive());
        assertTrue(coordinator.invoke(CONTEXT, callbackHandle + 1, new long[] {}) == 0L);
    }

    private FoundryRegistryCoordinator coordinator() {
        return new FoundryRegistryCoordinator(ConformanceFixture.bootstrap(), handle -> engine);
    }

    private FoundryRegistryCoordinator initializedCoordinator() {
        FoundryRegistryCoordinator coordinator = coordinator();
        for (FoundryInitializationLevel level :
                List.of(
                        FoundryInitializationLevel.CORE,
                        FoundryInitializationLevel.SERVERS,
                        FoundryInitializationLevel.SCENE)) {
            assertTrue(coordinator.initialize(CONTEXT, level.code()));
        }
        return coordinator;
    }

    private ConformanceSpinner referenceCounted(FoundryBindingContext context, long handle) {
        return context.bind(
                handle,
                games.cafecito.foundry.runtime.ObjectOwnership.REFERENCE_COUNTED,
                ConformanceSpinner.class,
                ConformanceSpinner::new);
    }

    private void awaitCleanerRelease(long handle) throws InterruptedException {
        AtomicBoolean released = new AtomicBoolean();
        for (int attempt = 0; attempt < 200 && !released.get(); attempt++) {
            System.gc();
            Thread.sleep(10L);
            released.set(engine.releaseCount(handle) == 1L);
        }
        assertTrue(
                "The Cleaner fallback did not release the abandoned wrapper exactly once; "
                        + "observed "
                        + engine.releaseCount(handle)
                        + " releases.",
                released.get());
    }
}
