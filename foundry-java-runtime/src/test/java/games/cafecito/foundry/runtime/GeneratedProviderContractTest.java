package games.cafecito.foundry.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeneratedProviderContractTest {
    @Test
    void bootstrapSortsProvidersAndExposesImmutableDescriptors() {
        ArrayList<FoundryModuleProvider> input =
                new ArrayList<>(
                        List.of(
                                provider("zeta", "example.Zeta"),
                                provider("alpha", "example.Alpha")));

        FoundryRegistryBootstrap bootstrap = new FoundryRegistryBootstrap(input);
        input.clear();

        assertEquals(List.of("alpha", "zeta"), bootstrap.moduleNames());
        assertEquals(
                List.of("example.Alpha", "example.Zeta"),
                bootstrap.descriptors().stream().map(FoundryModuleDescriptor::registry).toList());
        assertThrows(
                UnsupportedOperationException.class,
                () -> bootstrap.providers().add(provider("extra", "example.Extra")));
        assertThrows(
                UnsupportedOperationException.class,
                () -> bootstrap.descriptors().get(0).classes().add(classDescriptor()));
    }

    @Test
    void bootstrapRejectsDuplicateModulesAndRegistriesBeforePublishingProviders() {
        IllegalArgumentException duplicateModule =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new FoundryRegistryBootstrap(
                                        List.of(
                                                provider("demo", "example.First"),
                                                provider("demo", "example.Second"))));
        assertTrue(duplicateModule.getMessage().contains("module demo"));

        IllegalArgumentException duplicateRegistry =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new FoundryRegistryBootstrap(
                                        List.of(
                                                provider("first", "example.Shared"),
                                                provider("second", "example.Shared"))));
        assertTrue(duplicateRegistry.getMessage().contains("registry example.Shared"));
    }

    @Test
    void bootstrapRejectsMixedOrUnsupportedContracts() {
        assertContractRejected(
                descriptor(
                        "demo",
                        "example.Demo",
                        1,
                        FoundryRuntime.API_SHA256,
                        FoundryRuntime.GENERATOR_VERSION,
                        FoundryRuntime.RUNTIME_CONTRACT_VERSION,
                        FoundryRuntime.BRIDGE_CONTRACT_VERSION),
                "format 1");
        assertContractRejected(
                descriptor(
                        "demo",
                        "example.Demo",
                        2,
                        "0".repeat(64),
                        FoundryRuntime.GENERATOR_VERSION,
                        FoundryRuntime.RUNTIME_CONTRACT_VERSION,
                        FoundryRuntime.BRIDGE_CONTRACT_VERSION),
                "API SHA-256");
        assertContractRejected(
                descriptor(
                        "demo",
                        "example.Demo",
                        2,
                        FoundryRuntime.API_SHA256,
                        FoundryRuntime.GENERATOR_VERSION,
                        "999",
                        FoundryRuntime.BRIDGE_CONTRACT_VERSION),
                "runtime contract 999");
        assertContractRejected(
                descriptor(
                        "demo",
                        "example.Demo",
                        2,
                        FoundryRuntime.API_SHA256,
                        "999",
                        FoundryRuntime.RUNTIME_CONTRACT_VERSION,
                        FoundryRuntime.BRIDGE_CONTRACT_VERSION),
                "generator 999");
        assertContractRejected(
                descriptor(
                        "demo",
                        "example.Demo",
                        2,
                        FoundryRuntime.API_SHA256,
                        FoundryRuntime.GENERATOR_VERSION,
                        FoundryRuntime.RUNTIME_CONTRACT_VERSION,
                        "999"),
                "bridge contract 999");
    }

    @Test
    void providerHandshakeUsesTypedDirectCallsOnly() {
        TrackingProvider provider = new TrackingProvider(descriptor("demo", "example.Demo"));

        FoundryRegistryBootstrap bootstrap = new FoundryRegistryBootstrap(List.of(provider));

        assertEquals(1, provider.descriptorCalls);
        assertEquals(provider, bootstrap.providers().get(0));
        assertEquals("demo", bootstrap.descriptors().get(0).module());
    }

    /**
     * The engine resolves a parent class through {@code ClassDB}, whose names are never qualified.
     * A descriptor carrying the Java binding type's qualified name registers nothing, so the runtime
     * refuses it at descriptor construction instead of letting the engine reject the class.
     */
    @Test
    void classDescriptorRejectsAQualifiedJavaNameAsTheEngineParent() {
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new FoundryClassDescriptor(
                                        "example.Extension",
                                        "Extension",
                                        "games.cafecito.foundry.generated.classes.Node",
                                        "SCENE",
                                        List.of(),
                                        classDescriptor().access(),
                                        List.of()));

        assertTrue(
                failure.getMessage()
                        .contains("games.cafecito.foundry.generated.classes.Node"),
                failure::getMessage);
        assertTrue(failure.getMessage().contains("engine class name"), failure::getMessage);
    }

    private static FoundryModuleProvider provider(String module, String registry) {
        FoundryModuleDescriptor descriptor = descriptor(module, registry);
        return () -> descriptor;
    }

    private static FoundryModuleDescriptor descriptor(String module, String registry) {
        return descriptor(
                module,
                registry,
                2,
                FoundryRuntime.API_SHA256,
                FoundryRuntime.GENERATOR_VERSION,
                FoundryRuntime.RUNTIME_CONTRACT_VERSION,
                FoundryRuntime.BRIDGE_CONTRACT_VERSION);
    }

    private static FoundryModuleDescriptor descriptor(
            String module,
            String registry,
            int format,
            String apiSha256,
            String generatorVersion,
            String runtimeVersion,
            String bridgeVersion) {
        return new FoundryModuleDescriptor(
                format,
                module,
                registry,
                apiSha256,
                generatorVersion,
                runtimeVersion,
                bridgeVersion,
                List.of(classDescriptor()));
    }

    private static FoundryClassDescriptor classDescriptor() {
        FoundryExtensionAccess access =
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
                };
        return new FoundryClassDescriptor(
                "example.Extension",
                "Extension",
                "Node",
                "SCENE",
                List.of(),
                access,
                List.of(new FoundryMemberDescriptor("method", "run", "run", "void()")));
    }

    private static void assertContractRejected(
            FoundryModuleDescriptor descriptor, String expectedFragment) {
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new FoundryRegistryBootstrap(List.of(() -> descriptor)));
        assertTrue(failure.getMessage().contains(expectedFragment), failure::getMessage);
    }

    private static final class TrackingProvider implements FoundryModuleProvider {
        private final FoundryModuleDescriptor descriptor;
        private int descriptorCalls;

        private TrackingProvider(FoundryModuleDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public FoundryModuleDescriptor descriptor() {
            descriptorCalls++;
            return descriptor;
        }
    }
}
