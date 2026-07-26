package games.cafecito.foundry.gradle;

import java.util.List;
import java.util.Objects;

/** Strict, immutable representation of one processor-emitted format-2 descriptor. */
record FoundryDescriptor(
        String artifact,
        String descriptorPath,
        int format,
        String module,
        String registry,
        String apiSha256,
        String generatorVersion,
        String runtimeContractVersion,
        String bridgeContractVersion,
        List<Entry> entries) {
    FoundryDescriptor {
        artifact = Objects.requireNonNull(artifact, "artifact");
        descriptorPath = Objects.requireNonNull(descriptorPath, "descriptorPath");
        module = Objects.requireNonNull(module, "module");
        registry = Objects.requireNonNull(registry, "registry");
        apiSha256 = Objects.requireNonNull(apiSha256, "apiSha256");
        generatorVersion = Objects.requireNonNull(generatorVersion, "generatorVersion");
        runtimeContractVersion =
                Objects.requireNonNull(runtimeContractVersion, "runtimeContractVersion");
        bridgeContractVersion =
                Objects.requireNonNull(bridgeContractVersion, "bridgeContractVersion");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    String identity() {
        return descriptorPath.isBlank() ? artifact : artifact + "!" + descriptorPath;
    }

    record Entry(String kind, String value) {
        Entry {
            kind = Objects.requireNonNull(kind, "kind");
            value = Objects.requireNonNull(value, "value");
        }
    }
}
