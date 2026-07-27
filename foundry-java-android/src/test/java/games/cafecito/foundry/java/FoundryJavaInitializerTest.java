package games.cafecito.foundry.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.cafecito.foundry.runtime.FoundryBridgeCallbacks;
import games.cafecito.foundry.runtime.FoundryEngine;
import games.cafecito.foundry.runtime.FoundryModuleDescriptor;
import games.cafecito.foundry.runtime.FoundryModuleProvider;
import games.cafecito.foundry.runtime.FoundryRegistryBootstrap;
import games.cafecito.foundry.runtime.FoundryRuntime;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FoundryJavaInitializerTest {
    @Test
    void fixedConfigurationSelectsOnlyTheFourBridgeAbis() throws IOException {
        String configuration =
                new String(
                        Files.readAllBytes(
                                Path.of("src/main/resources/FoundryJava.foundryextension")),
                        StandardCharsets.UTF_8);

        assertEquals(
                """
                [configuration]

                entry_symbol = "foundry_java_library_init"

                [libraries]

                android.arm32 = "libfoundry_java.so"
                android.arm64 = "libfoundry_java.so"
                android.x86_32 = "libfoundry_java.so"
                android.x86_64 = "libfoundry_java.so"
                """,
                configuration);
        assertFalse(configuration.contains("lib" + "foundry_android.so"));
        assertFalse(configuration.contains("manifest"));
    }

    @Test
    void consumerRulesKeepOnlyFixedBootstrapProviderAndCallbackEntrypoints() throws IOException {
        String rules =
                new String(
                        Files.readAllBytes(Path.of("src/main/consumer-rules.pro")),
                        StandardCharsets.UTF_8);

        assertTrue(rules.contains("FoundryJavaInitializer"));
        assertTrue(rules.contains("FoundryGeneratedBootstrap"));
        assertTrue(rules.contains("FoundryGeneratedStartupProvider"));
        assertTrue(rules.contains("FoundryJavaStartupProvider"));
        assertTrue(rules.contains("FoundryModuleProvider"));
        assertTrue(rules.contains("FoundryBridgeCallbacks"));
        assertFalse(rules.contains("games.cafecito.foundry.**"));
        assertFalse(rules.contains("-keep class *"));
        assertFalse(rules.contains("FoundryPlugin"));
    }

    @Test
    void typedInitializerHandshakeIsPartOfTheCompileTimeContract() {
        TypedInitializer initializer = FoundryJavaInitializer::initialize;

        assertNotNull(initializer);
    }

    @Test
    void productionEngineConstructionIsDeferredUntilCoreInitialization() {
        AtomicInteger constructions = new AtomicInteger();
        AtomicReference<FoundryBridgeCallbacks> primedCallbacks = new AtomicReference<>();
        FoundryJavaInitializer.PrimingState state =
                new FoundryJavaInitializer.PrimingState(
                        () -> {},
                        (loader, callbacks) -> {
                            primedCallbacks.set(callbacks);
                            return true;
                        },
                        ignored -> {
                            constructions.incrementAndGet();
                            return noOpEngine();
                        },
                        ignored -> {});

        state.prime(getClass().getClassLoader(), new FoundryRegistryBootstrap(List.of()));

        assertEquals(0, constructions.get());
        assertFalse(primedCallbacks.get().initialize(91, 1));
        assertEquals(0, constructions.get());
        assertTrue(primedCallbacks.get().initialize(91, 0));
        assertEquals(1, constructions.get());
        assertTrue(primedCallbacks.get().initialize(91, 0));
        assertEquals(1, constructions.get());
    }

    @Test
    void productionFactoryCreatesTheGeneratedDispatchEngine() throws Exception {
        var factory =
                FoundryJavaInitializer.class.getDeclaredMethod(
                        "createProductionEngine", long.class);
        factory.setAccessible(true);

        assertInstanceOf(FoundryNativeEngine.class, factory.invoke(null, 91L));
    }

    @Test
    void diagnosticsAreStableMachineReadableAndIncludeEveryContractField() {
        FoundryRegistryBootstrap bootstrap =
                new FoundryRegistryBootstrap(
                        List.of(
                                provider("zeta", "example.Zeta"),
                                provider("alpha", "example.Alpha")));

        assertEquals(
                "{\"api_sha256\":\""
                        + FoundryRuntime.API_SHA256
                        + "\",\"generator_version\":\""
                        + FoundryRuntime.GENERATOR_VERSION
                        + "\",\"runtime_contract_version\":\""
                        + FoundryRuntime.RUNTIME_CONTRACT_VERSION
                        + "\",\"bridge_contract_version\":\""
                        + FoundryRuntime.BRIDGE_CONTRACT_VERSION
                        + "\",\"registry_modules\":[\"alpha\",\"zeta\"],"
                        + "\"initialization_level\":2,\"failure_phase\":\"registration\"}",
                FoundryJavaInitializer.diagnosticJson(bootstrap, 2, "registration"));
    }

    @Test
    void diagnosticCallbacksReportInitializationSuccessAndFailure() {
        FoundryRegistryBootstrap bootstrap =
                new FoundryRegistryBootstrap(List.of(provider("demo", "example.Demo")));
        RecordingCallbacks accepted = new RecordingCallbacks(true);
        FoundryBridgeCallbacks loggingAccepted =
                FoundryJavaInitializer.diagnosticCallbacks(
                        bootstrap, accepted, accepted::recordDiagnostic);

        assertTrue(loggingAccepted.initialize(41, 1));
        assertEquals(
                FoundryJavaInitializer.diagnosticJson(bootstrap, 1, "none"),
                accepted.lastDiagnostic);

        RecordingCallbacks rejected = new RecordingCallbacks(false);
        FoundryBridgeCallbacks loggingRejected =
                FoundryJavaInitializer.diagnosticCallbacks(
                        bootstrap, rejected, rejected::recordDiagnostic);

        assertFalse(loggingRejected.initialize(42, 3));
        assertEquals(
                FoundryJavaInitializer.diagnosticJson(bootstrap, 3, "initialization_callback"),
                rejected.lastDiagnostic);
    }

    private static FoundryModuleProvider provider(String module, String registry) {
        FoundryModuleDescriptor descriptor =
                new FoundryModuleDescriptor(
                        FoundryModuleDescriptor.CURRENT_FORMAT,
                        module,
                        registry,
                        FoundryRuntime.API_SHA256,
                        FoundryRuntime.GENERATOR_VERSION,
                        FoundryRuntime.RUNTIME_CONTRACT_VERSION,
                        FoundryRuntime.BRIDGE_CONTRACT_VERSION,
                        List.of());
        return () -> descriptor;
    }

    private static FoundryEngine noOpEngine() {
        return (FoundryEngine)
                Proxy.newProxyInstance(
                        FoundryEngine.class.getClassLoader(),
                        new Class<?>[] {FoundryEngine.class},
                        (proxy, method, arguments) ->
                                switch (method.getReturnType().getName()) {
                                    case "boolean" -> false;
                                    case "long" -> 0L;
                                    default -> null;
                                });
    }

    @FunctionalInterface
    private interface TypedInitializer {
        boolean initialize(FoundryRegistryBootstrap bootstrap, FoundryBridgeCallbacks callbacks);
    }

    private static final class RecordingCallbacks implements FoundryBridgeCallbacks {
        private final boolean initializeResult;
        private String lastDiagnostic;

        private RecordingCallbacks(boolean initializeResult) {
            this.initializeResult = initializeResult;
        }

        private void recordDiagnostic(String diagnostic) {
            lastDiagnostic = diagnostic;
        }

        @Override
        public boolean initialize(long contextHandle, int level) {
            return initializeResult;
        }

        @Override
        public void deinitialize(long contextHandle, int level) {}

        @Override
        public long invoke(long contextHandle, long callbackHandle, long[] argumentHandles) {
            return 0;
        }

        @Override
        public void invalidate(long contextHandle) {}
    }
}
