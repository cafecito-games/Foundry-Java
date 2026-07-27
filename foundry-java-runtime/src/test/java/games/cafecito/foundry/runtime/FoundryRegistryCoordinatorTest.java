package games.cafecito.foundry.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.cafecito.foundry.types.Variant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FoundryRegistryCoordinatorTest {
    @Test
    void wholeBootstrapUsesQualifiedDependenciesAndStableCrossModuleOrder() {
        FoundryRegistryBootstrap bootstrap =
                bootstrap(
                        provider(
                                "zeta",
                                type("example.Leaf", "Leaf", "SCENE", "example.Base")),
                        provider("alpha", type("example.Base", "Base", "CORE")),
                        provider("middle", type("example.Other", "Other", "CORE")));

        FoundryRegistrationPlan plan = FoundryRegistrationPlan.create(bootstrap);

        assertEquals(
                List.of("example.Base", "example.Other", "example.Leaf"),
                plan.orderedClasses().stream().map(FoundryClassDescriptor::javaName).toList());
    }

    @Test
    void invalidWholeGraphsFailBeforeAnyEngineMutation() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FoundryRegistrationPlan.create(
                                bootstrap(
                                        provider(
                                                "missing",
                                                type(
                                                        "example.Leaf",
                                                        "Leaf",
                                                        "SCENE",
                                                        "example.Absent")))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FoundryRegistrationPlan.create(
                                bootstrap(
                                        provider(
                                                "cycle",
                                                type("example.A", "A", "CORE", "example.B"),
                                                type("example.B", "B", "CORE", "example.A")))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FoundryRegistrationPlan.create(
                                bootstrap(
                                        provider("one", type("example.A", "Shared", "CORE")),
                                        provider("two", type("example.B", "Shared", "CORE")))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FoundryRegistrationPlan.create(
                                bootstrap(
                                        provider(
                                                "levels",
                                                type("example.Core", "Core", "CORE", "example.Scene"),
                                                type("example.Scene", "Scene", "SCENE")))));
    }

    @Test
    void contextExistsBeforeCoreRegistrationAndLevelsRegisterExactlyOnce() {
        RecordingEngine engine = new RecordingEngine();
        FoundryRegistryCoordinator coordinator =
                new FoundryRegistryCoordinator(
                        bootstrap(
                                provider(
                                        "demo",
                                        type("example.Core", "Core", "CORE"),
                                        type("example.Scene", "Scene", "SCENE"))),
                        context -> {
                            engine.events.add("context:" + context);
                            engine.contextHandle = context;
                            return engine;
                        });

        assertTrue(coordinator.initialize(41, FoundryInitializationLevel.CORE.code()));
        assertTrue(coordinator.initialize(41, FoundryInitializationLevel.CORE.code()));
        assertTrue(coordinator.initialize(41, FoundryInitializationLevel.SERVERS.code()));
        assertTrue(coordinator.initialize(41, FoundryInitializationLevel.SCENE.code()));

        assertEquals(
                List.of("context:41", "register:Core", "register:Scene"), engine.events);
    }

    @Test
    void deinitializationUnregistersEachLevelInReversePlanOrder() {
        RecordingEngine engine = new RecordingEngine();
        FoundryRegistryCoordinator coordinator =
                coordinator(
                        engine,
                        type("example.A", "A", "CORE"),
                        type("example.B", "B", "CORE", "example.A"),
                        type("example.C", "C", "SCENE"));
        assertTrue(coordinator.initialize(41, FoundryInitializationLevel.CORE.code()));
        assertTrue(coordinator.initialize(41, FoundryInitializationLevel.SERVERS.code()));
        assertTrue(coordinator.initialize(41, FoundryInitializationLevel.SCENE.code()));

        coordinator.deinitialize(41, FoundryInitializationLevel.SCENE.code());
        coordinator.deinitialize(41, FoundryInitializationLevel.SERVERS.code());
        coordinator.deinitialize(41, FoundryInitializationLevel.CORE.code());

        assertEquals(
                List.of(
                        "register:A",
                        "register:B",
                        "register:C",
                        "unregister:C",
                        "unregister:B",
                        "unregister:A"),
                engine.events);
    }

    @Test
    void laterLevelFailureRollsBackEveryLiveLevelInGlobalReverseOrder() {
        RecordingEngine engine = new RecordingEngine();
        engine.failRegistration = "D";
        FoundryRegistryCoordinator coordinator =
                coordinator(
                        engine,
                        type("example.A", "A", "CORE"),
                        type("example.B", "B", "CORE", "example.A"),
                        type("example.C", "C", "SERVERS"),
                        type("example.D", "D", "SCENE"));
        assertTrue(coordinator.initialize(41, FoundryInitializationLevel.CORE.code()));
        assertTrue(coordinator.initialize(41, FoundryInitializationLevel.SERVERS.code()));

        assertFalse(coordinator.initialize(41, FoundryInitializationLevel.SCENE.code()));

        assertEquals(
                List.of(
                        "register:A",
                        "register:B",
                        "register:C",
                        "register:D",
                        "unregister:C",
                        "unregister:B",
                        "unregister:A"),
                engine.events);
        assertEquals(1, engine.contextCloses.get());
    }

    @Test
    void prematureCoreDeinitializeRollsBackEveryLiveLevelInGlobalReverseOrder() {
        RecordingEngine engine = new RecordingEngine();
        FoundryRegistryCoordinator coordinator =
                coordinator(
                        engine,
                        type("example.A", "A", "CORE"),
                        type("example.B", "B", "SERVERS"),
                        type("example.C", "C", "SCENE"),
                        type("example.D", "D", "EDITOR"));
        assertTrue(coordinator.initialize(41, FoundryInitializationLevel.CORE.code()));
        assertTrue(coordinator.initialize(41, FoundryInitializationLevel.SERVERS.code()));
        assertTrue(coordinator.initialize(41, FoundryInitializationLevel.SCENE.code()));
        assertTrue(coordinator.initialize(41, FoundryInitializationLevel.EDITOR.code()));

        coordinator.deinitialize(41, FoundryInitializationLevel.CORE.code());
        coordinator.invalidate(41);

        assertEquals(
                List.of(
                        "register:A",
                        "register:B",
                        "register:C",
                        "register:D",
                        "unregister:D",
                        "unregister:C",
                        "unregister:B",
                        "unregister:A"),
                engine.events);
        assertEquals(1, engine.contextCloses.get());
    }

    @Test
    void initializationAndNonCoreTeardownRejectSkippedOrOutOfOrderLevels() {
        RecordingEngine engine = new RecordingEngine();
        FoundryRegistryCoordinator coordinator =
                coordinator(
                        engine,
                        type("example.A", "A", "CORE"),
                        type("example.B", "B", "SERVERS"),
                        type("example.C", "C", "SCENE"),
                        type("example.D", "D", "EDITOR"));

        assertFalse(coordinator.initialize(41, FoundryInitializationLevel.SCENE.code()));
        assertTrue(coordinator.initialize(41, FoundryInitializationLevel.CORE.code()));
        assertFalse(coordinator.initialize(41, FoundryInitializationLevel.SCENE.code()));
        assertTrue(coordinator.initialize(41, FoundryInitializationLevel.SERVERS.code()));
        assertFalse(coordinator.initialize(41, FoundryInitializationLevel.EDITOR.code()));
        assertTrue(coordinator.initialize(41, FoundryInitializationLevel.SCENE.code()));
        assertTrue(coordinator.initialize(41, FoundryInitializationLevel.EDITOR.code()));

        coordinator.deinitialize(41, FoundryInitializationLevel.SERVERS.code());
        coordinator.deinitialize(41, FoundryInitializationLevel.EDITOR.code());
        coordinator.deinitialize(41, FoundryInitializationLevel.SERVERS.code());
        coordinator.deinitialize(41, FoundryInitializationLevel.SCENE.code());
        coordinator.deinitialize(41, FoundryInitializationLevel.SERVERS.code());
        coordinator.deinitialize(41, FoundryInitializationLevel.CORE.code());

        assertEquals(
                List.of(
                        "register:A",
                        "register:B",
                        "register:C",
                        "register:D",
                        "unregister:D",
                        "unregister:C",
                        "unregister:B",
                        "unregister:A"),
                engine.events);
    }

    @Test
    void lifecycleReentryFromEngineOperationsFailsFastWithoutDeadlock() throws Exception {
        RecordingEngine engine = new RecordingEngine();
        AtomicReference<FoundryRegistryCoordinator> reference = new AtomicReference<>();
        FoundryRegistryCoordinator coordinator =
                new FoundryRegistryCoordinator(
                        bootstrap(
                                provider(
                                        "demo",
                                        type("example.A", "A", "CORE"),
                                        type("example.B", "B", "SERVERS"))),
                        context -> {
                            reference.get().invalidate(context);
                            reference
                                    .get()
                                    .deinitialize(
                                            context, FoundryInitializationLevel.CORE.code());
                            engine.contextHandle = context;
                            return engine;
                        },
                        context -> engine.contextCloses.incrementAndGet());
        reference.set(coordinator);
        engine.registerReentry =
                () -> {
                    coordinator.invalidate(41);
                    coordinator.deinitialize(41, FoundryInitializationLevel.CORE.code());
                };
        engine.unregisterReentry =
                () -> {
                    coordinator.invalidate(41);
                    coordinator.deinitialize(41, FoundryInitializationLevel.CORE.code());
                };

        AtomicReference<Boolean> result = new AtomicReference<>();
        Thread initialize =
                new Thread(
                        () ->
                                result.set(
                                        coordinator.initialize(
                                                41, FoundryInitializationLevel.CORE.code())));
        initialize.setDaemon(true);
        initialize.start();
        initialize.join(TimeUnit.SECONDS.toMillis(2));

        assertFalse(initialize.isAlive());
        assertEquals(Boolean.TRUE, result.get());
        assertTrue(coordinator.initialize(41, FoundryInitializationLevel.SERVERS.code()));
        coordinator.deinitialize(41, FoundryInitializationLevel.SERVERS.code());
        coordinator.deinitialize(41, FoundryInitializationLevel.CORE.code());
        assertEquals(1, engine.contextCloses.get());
    }

    @Test
    void concurrentLifecycleContendersReturnWithoutWaitingForRegistration() throws Exception {
        RecordingEngine engine = new RecordingEngine();
        CountDownLatch registrationStarted = new CountDownLatch(1);
        CountDownLatch releaseRegistration = new CountDownLatch(1);
        engine.registerReentry =
                () -> {
                    registrationStarted.countDown();
                    await(releaseRegistration);
                };
        FoundryRegistryCoordinator coordinator =
                coordinator(engine, type("example.A", "A", "CORE"));
        AtomicReference<Boolean> initialization = new AtomicReference<>();
        Thread initialize =
                new Thread(
                        () ->
                                initialization.set(
                                        coordinator.initialize(
                                                41, FoundryInitializationLevel.CORE.code())));
        initialize.start();
        assertTrue(registrationStarted.await(2, TimeUnit.SECONDS));

        AtomicReference<Boolean> contenderInitialization = new AtomicReference<>();
        Thread contender =
                new Thread(
                        () -> {
                            contenderInitialization.set(
                                    coordinator.initialize(
                                            41, FoundryInitializationLevel.CORE.code()));
                            coordinator.deinitialize(
                                    41, FoundryInitializationLevel.CORE.code());
                            coordinator.invalidate(41);
                        });
        contender.setDaemon(true);
        contender.start();
        contender.join(500);
        boolean contenderReturnedWhileRegistrationBlocked = !contender.isAlive();
        releaseRegistration.countDown();
        initialize.join(TimeUnit.SECONDS.toMillis(2));
        contender.join(TimeUnit.SECONDS.toMillis(2));

        assertTrue(contenderReturnedWhileRegistrationBlocked);
        assertFalse(initialize.isAlive());
        assertEquals(Boolean.TRUE, initialization.get());
        assertEquals(Boolean.FALSE, contenderInitialization.get());
        coordinator.invalidate(41);
        assertEquals(1, engine.contextCloses.get());
    }

    @Test
    void partialRegistrationFailureRollsBackCompletedClassesAndTerminatesOnce() {
        RecordingEngine engine = new RecordingEngine();
        engine.failRegistration = "B";
        FoundryRegistryCoordinator coordinator =
                coordinator(
                        engine,
                        type("example.A", "A", "CORE"),
                        type("example.B", "B", "CORE", "example.A"),
                        type("example.C", "C", "CORE", "example.B"));

        assertFalse(coordinator.initialize(41, FoundryInitializationLevel.CORE.code()));
        coordinator.invalidate(41);
        coordinator.deinitialize(41, FoundryInitializationLevel.CORE.code());

        assertEquals(List.of("register:A", "register:B", "unregister:A"), engine.events);
        assertEquals(1, engine.contextCloses.get());
    }

    @Test
    void concurrentInvalidationAndCoreDeinitializeCloseTheContextOnce() throws Exception {
        RecordingEngine engine = new RecordingEngine();
        FoundryRegistryCoordinator coordinator =
                coordinator(engine, type("example.A", "A", "CORE"));
        assertTrue(coordinator.initialize(41, FoundryInitializationLevel.CORE.code()));
        CountDownLatch start = new CountDownLatch(1);
        Thread invalidate =
                new Thread(
                        () -> {
                            await(start);
                            coordinator.invalidate(41);
                        });
        Thread deinitialize =
                new Thread(
                        () -> {
                            await(start);
                            coordinator.deinitialize(41, FoundryInitializationLevel.CORE.code());
                        });
        invalidate.start();
        deinitialize.start();
        start.countDown();
        invalidate.join(TimeUnit.SECONDS.toMillis(5));
        deinitialize.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(invalidate.isAlive());
        assertFalse(deinitialize.isAlive());
        assertEquals(1, engine.contextCloses.get());
        assertEquals(1, engine.events.stream().filter("unregister:A"::equals).count());
    }

    private static FoundryRegistryCoordinator coordinator(
            RecordingEngine engine, FoundryClassDescriptor... descriptors) {
        return new FoundryRegistryCoordinator(
                bootstrap(provider("demo", descriptors)),
                context -> {
                    engine.contextHandle = context;
                    return engine;
                },
                context -> engine.contextCloses.incrementAndGet());
    }

    private static FoundryRegistryBootstrap bootstrap(FoundryModuleProvider... providers) {
        return new FoundryRegistryBootstrap(List.of(providers));
    }

    private static FoundryModuleProvider provider(
            String module, FoundryClassDescriptor... descriptors) {
        FoundryModuleDescriptor descriptor =
                new FoundryModuleDescriptor(
                        FoundryModuleDescriptor.CURRENT_FORMAT,
                        module,
                        "generated." + module,
                        FoundryRuntime.API_SHA256,
                        FoundryRuntime.GENERATOR_VERSION,
                        FoundryRuntime.RUNTIME_CONTRACT_VERSION,
                        FoundryRuntime.BRIDGE_CONTRACT_VERSION,
                        List.of(descriptors));
        return () -> descriptor;
    }

    private static FoundryClassDescriptor type(
            String javaName, String foundryName, String level, String... after) {
        return new FoundryClassDescriptor(
                javaName,
                foundryName,
                "Node",
                level,
                List.of(after),
                new FoundryExtensionAccess() {
                    @Override
                    public Object construct(FoundryBindingContext context, ObjectLease lease) {
                        return new Object();
                    }

                    @Override
                    public Object invoke(Object target, String name, Object[] arguments) {
                        return null;
                    }

                    @Override
                    public Object getProperty(Object target, String name) {
                        return null;
                    }

                    @Override
                    public void setProperty(Object target, String name, Object value) {}
                },
                List.of());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static final class RecordingEngine implements FoundryEngine {
        private final List<String> events = new ArrayList<>();
        private final AtomicInteger contextCloses = new AtomicInteger();
        private String failRegistration;
        private long contextHandle;
        private Runnable registerReentry = () -> {};
        private Runnable unregisterReentry = () -> {};

        @Override
        public void registerExtensionClass(
                long contextHandle, FoundryClassDescriptor descriptor) {
            assertEquals(this.contextHandle, contextHandle);
            events.add("register:" + descriptor.foundryName());
            registerReentry.run();
            if (descriptor.foundryName().equals(failRegistration)) {
                throw new IllegalStateException("registration failed");
            }
        }

        @Override
        public void unregisterExtensionClass(long contextHandle, String foundryName) {
            assertEquals(this.contextHandle, contextHandle);
            events.add("unregister:" + foundryName);
            unregisterReentry.run();
        }

        @Override
        public CallResult call(
                long contextHandle,
                long objectHandle,
                String methodIdentity,
                List<Variant> arguments) {
            return CallResult.success(Variant.nil());
        }

        @Override
        public Variant decodeVariant(long contextHandle, long variantHandle) {
            return Variant.nil();
        }

        @Override
        public long encodeVariant(long contextHandle, Variant value) {
            return 0;
        }

        @Override
        public boolean isObjectValid(long contextHandle, long objectHandle) {
            return true;
        }

        @Override
        public String objectType(long contextHandle, long objectHandle) {
            return "";
        }

        @Override
        public long instantiate(long contextHandle, String className) {
            return 1;
        }

        @Override
        public void retain(long contextHandle, long objectHandle) {}

        @Override
        public void release(long contextHandle, long objectHandle) {}

        @Override
        public long singleton(long contextHandle, String name) {
            return 1;
        }

        @Override
        public void reportCallbackException(
                long contextHandle, long callbackHandle, Throwable failure) {}
    }
}
