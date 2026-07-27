package games.cafecito.foundry.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.cafecito.foundry.runtime.FoundryBridgeCallbacks;
import games.cafecito.foundry.runtime.FoundryRegistryBootstrap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FoundryJavaStartupProviderTest {
    @Test
    void providerPassesItsTypedBootstrapAndApplicationClassLoaderToThePrimer() {
        FoundryRegistryBootstrap bootstrap = new FoundryRegistryBootstrap(List.of());
        ClassLoader loader = new ClassLoader() {};
        AtomicReference<ClassLoader> primedLoader = new AtomicReference<>();
        AtomicReference<FoundryRegistryBootstrap> primedBootstrap = new AtomicReference<>();
        TestProvider provider =
                new TestProvider(
                        bootstrap,
                        loader,
                        (actualLoader, actualBootstrap) -> {
                            primedLoader.set(actualLoader);
                            primedBootstrap.set(actualBootstrap);
                        });

        assertTrue(provider.onCreate());
        assertSame(loader, primedLoader.get());
        assertSame(bootstrap, primedBootstrap.get());
    }

    @Test
    void providerCrudSurfaceIsADeterministicNoOp() {
        TestProvider provider =
                new TestProvider(
                        new FoundryRegistryBootstrap(List.of()),
                        new ClassLoader() {},
                        (loader, bootstrap) -> {});

        assertNull(provider.query(null, null, null, null, null));
        assertNull(provider.getType(null));
        assertNull(provider.insert(null, null));
        assertEquals(0, provider.delete(null, null, null));
        assertEquals(0, provider.update(null, null, null, null));
    }

    @Test
    void providerQualifiesBootstrapHookFailuresWithThePreEntryPhase() {
        TestProvider provider =
                new TestProvider(null, new ClassLoader() {}, (loader, bootstrap) -> {});

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, provider::onCreate);

        assertTrue(failure.getMessage().contains("failure_phase=provider_pre_entry"));
    }

    @Test
    void primingValidatesAndInstallsCallbacksWithoutCreatingAnEngineContext() {
        FoundryRegistryBootstrap bootstrap = new FoundryRegistryBootstrap(List.of());
        ClassLoader loader = new ClassLoader() {};
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger nativeBootstraps = new AtomicInteger();
        AtomicInteger engines = new AtomicInteger();
        AtomicReference<FoundryBridgeCallbacks> callbacks = new AtomicReference<>();
        FoundryJavaInitializer.PrimingState state =
                new FoundryJavaInitializer.PrimingState(
                        loads::incrementAndGet,
                        (actualLoader, actualCallbacks) -> {
                            assertSame(loader, actualLoader);
                            callbacks.set(actualCallbacks);
                            nativeBootstraps.incrementAndGet();
                            return true;
                        },
                        contextHandle -> {
                            engines.incrementAndGet();
                            throw new AssertionError(
                                    "Provider priming must not construct an engine.");
                        },
                        ignored -> {});

        state.prime(loader, bootstrap);
        state.prime(loader, bootstrap);

        assertEquals(1, loads.get());
        assertEquals(1, nativeBootstraps.get());
        assertEquals(0, engines.get());
        assertFalse(callbacks.get().initialize(0, 0));
    }

    @Test
    void differentBootstrapAndRestartLikeClassLoaderFailClosed() {
        FoundryRegistryBootstrap first = new FoundryRegistryBootstrap(List.of());
        FoundryRegistryBootstrap second = new FoundryRegistryBootstrap(List.of());
        ClassLoader loader = new ClassLoader() {};
        FoundryJavaInitializer.PrimingState state = successfulState();

        state.prime(loader, first);

        IllegalStateException differentBootstrap =
                assertThrows(IllegalStateException.class, () -> state.prime(loader, second));
        assertTrue(differentBootstrap.getMessage().contains("failure_phase=provider_pre_entry"));
        IllegalStateException differentLoader =
                assertThrows(
                        IllegalStateException.class,
                        () -> state.prime(new ClassLoader() {}, first));
        assertTrue(differentLoader.getMessage().contains("failure_phase=provider_pre_entry"));
    }

    @Test
    void activeReentryAndStaleFailedStateFailWithProviderPhase() {
        FoundryRegistryBootstrap bootstrap = new FoundryRegistryBootstrap(List.of());
        ClassLoader loader = new ClassLoader() {};
        AtomicReference<FoundryJavaInitializer.PrimingState> active = new AtomicReference<>();
        AtomicReference<RuntimeException> reentryFailure = new AtomicReference<>();
        AtomicInteger diagnostics = new AtomicInteger();
        FoundryJavaInitializer.PrimingState reentrant =
                new FoundryJavaInitializer.PrimingState(
                        () -> {},
                        (actualLoader, callbacks) -> {
                            try {
                                active.get().prime(actualLoader, bootstrap);
                            } catch (RuntimeException failure) {
                                reentryFailure.set(failure);
                            }
                            return true;
                        },
                        contextHandle -> {
                            throw new AssertionError(
                                    "Provider priming must not construct an engine.");
                        },
                        ignored -> diagnostics.incrementAndGet());
        active.set(reentrant);

        reentrant.prime(loader, bootstrap);

        assertTrue(reentryFailure.get().getMessage().contains("failure_phase=provider_pre_entry"));
        assertEquals(1, diagnostics.get());

        FoundryJavaInitializer.PrimingState failed =
                new FoundryJavaInitializer.PrimingState(
                        () -> {},
                        (actualLoader, callbacks) -> false,
                        contextHandle -> {
                            throw new AssertionError(
                                    "Provider priming must not construct an engine.");
                        },
                        ignored -> {});
        IllegalStateException initial =
                assertThrows(IllegalStateException.class, () -> failed.prime(loader, bootstrap));
        assertTrue(initial.getMessage().contains("failure_phase=provider_pre_entry"));
        IllegalStateException stale =
                assertThrows(IllegalStateException.class, () -> failed.prime(loader, bootstrap));
        assertTrue(stale.getMessage().contains("failure_phase=provider_pre_entry"));
    }

    private static FoundryJavaInitializer.PrimingState successfulState() {
        return new FoundryJavaInitializer.PrimingState(
                () -> {},
                (loader, callbacks) -> true,
                contextHandle -> {
                    throw new AssertionError("Provider priming must not construct an engine.");
                },
                ignored -> {});
    }

    private static final class TestProvider extends FoundryJavaStartupProvider {
        private final FoundryRegistryBootstrap bootstrap;
        private final ClassLoader loader;

        private TestProvider(
                FoundryRegistryBootstrap bootstrap, ClassLoader loader, Primer primer) {
            super(primer);
            this.bootstrap = bootstrap;
            this.loader = loader;
        }

        @Override
        protected FoundryRegistryBootstrap bootstrap() {
            return bootstrap;
        }

        @Override
        ClassLoader applicationClassLoader() {
            return loader;
        }
    }
}
