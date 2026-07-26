package games.cafecito.foundry.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Validated, deterministic registry handoff generated into an Android application.
 *
 * <p>The generated bootstrap constructs providers directly. This class never scans a classpath,
 * reads manifest metadata, or uses reflection.
 */
public final class FoundryRegistryBootstrap {
    private final List<FoundryModuleProvider> providers;
    private final List<FoundryModuleDescriptor> descriptors;
    private final List<String> moduleNames;

    public FoundryRegistryBootstrap(List<? extends FoundryModuleProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        List<Entry> entries = new ArrayList<>(providers.size());
        for (FoundryModuleProvider provider : providers) {
            FoundryModuleProvider checkedProvider = Objects.requireNonNull(provider, "provider");
            FoundryModuleDescriptor descriptor =
                    Objects.requireNonNull(checkedProvider.descriptor(), "provider descriptor");
            validateContract(descriptor);
            entries.add(new Entry(checkedProvider, descriptor));
        }
        entries.sort(
                Comparator.comparing((Entry entry) -> entry.descriptor().module())
                        .thenComparing(entry -> entry.descriptor().registry()));
        rejectDuplicates(entries);
        this.providers = entries.stream().map(Entry::provider).toList();
        this.descriptors = entries.stream().map(Entry::descriptor).toList();
        moduleNames = descriptors.stream().map(FoundryModuleDescriptor::module).toList();
    }

    public List<FoundryModuleProvider> providers() {
        return providers;
    }

    public List<FoundryModuleDescriptor> descriptors() {
        return descriptors;
    }

    public List<String> moduleNames() {
        return moduleNames;
    }

    private static void validateContract(FoundryModuleDescriptor descriptor) {
        if (descriptor.format() != FoundryModuleDescriptor.CURRENT_FORMAT) {
            throw new IllegalArgumentException(
                    "Foundry module "
                            + descriptor.module()
                            + " uses descriptor format "
                            + descriptor.format()
                            + "; expected "
                            + FoundryModuleDescriptor.CURRENT_FORMAT
                            + ".");
        }
        requireContract(
                descriptor, "API SHA-256", descriptor.apiSha256(), FoundryRuntime.API_SHA256);
        requireContract(
                descriptor,
                "generator",
                descriptor.generatorVersion(),
                FoundryRuntime.GENERATOR_VERSION);
        requireContract(
                descriptor,
                "runtime contract",
                descriptor.runtimeContractVersion(),
                FoundryRuntime.RUNTIME_CONTRACT_VERSION);
        requireContract(
                descriptor,
                "bridge contract",
                descriptor.bridgeContractVersion(),
                FoundryRuntime.BRIDGE_CONTRACT_VERSION);
    }

    private static void requireContract(
            FoundryModuleDescriptor descriptor, String name, String actual, String expected) {
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                    "Foundry module "
                            + descriptor.module()
                            + " uses "
                            + name
                            + " "
                            + actual
                            + "; expected "
                            + expected
                            + ".");
        }
    }

    private static void rejectDuplicates(List<Entry> entries) {
        Set<String> modules = new HashSet<>();
        Set<String> registries = new HashSet<>();
        for (Entry entry : entries) {
            FoundryModuleDescriptor descriptor = entry.descriptor();
            if (!modules.add(descriptor.module())) {
                throw new IllegalArgumentException(
                        "Duplicate Foundry module " + descriptor.module() + ".");
            }
            if (!registries.add(descriptor.registry())) {
                throw new IllegalArgumentException(
                        "Duplicate Foundry registry " + descriptor.registry() + ".");
            }
        }
    }

    private record Entry(FoundryModuleProvider provider, FoundryModuleDescriptor descriptor) {}
}
