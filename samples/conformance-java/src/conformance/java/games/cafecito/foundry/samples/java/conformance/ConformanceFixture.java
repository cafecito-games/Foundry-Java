package games.cafecito.foundry.samples.java.conformance;

import games.cafecito.foundry.generated.samplesjava.SamplesJavaRegistry;
import games.cafecito.foundry.runtime.FoundryClassDescriptor;
import games.cafecito.foundry.runtime.FoundryModuleDescriptor;
import games.cafecito.foundry.runtime.FoundryRegistryBootstrap;
import java.util.List;

/**
 * Shared, public-API-only helpers for the conformance matrix.
 *
 * <p>Everything here is reachable by any consumer of the published artifacts. The matrix never
 * reaches into Foundry-Java internals or test fixtures.
 */
final class ConformanceFixture {
    static final long CONTEXT_HANDLE = 1L;
    static final String CORE_CLASS = "ConformanceCatalog";
    static final String SCENE_CLASS = "ConformanceSpinner";

    private ConformanceFixture() {}

    static FoundryRegistryBootstrap bootstrap() {
        return new FoundryRegistryBootstrap(List.of(SamplesJavaRegistry.PROVIDER));
    }

    static FoundryModuleDescriptor moduleDescriptor() {
        return SamplesJavaRegistry.PROVIDER.descriptor();
    }

    static FoundryClassDescriptor classDescriptor(String foundryName) {
        return moduleDescriptor().classes().stream()
                .filter(descriptor -> descriptor.foundryName().equals(foundryName))
                .findFirst()
                .orElseThrow(
                        () ->
                                new AssertionError(
                                        "The sample module does not declare " + foundryName));
    }
}
