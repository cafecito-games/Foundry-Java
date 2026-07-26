package games.cafecito.foundry.runtime;

import java.util.List;
import java.util.Objects;

/** Immutable, provenance-bound descriptor emitted for one Java extension module. */
public record FoundryModuleDescriptor(
        int format,
        String module,
        String registry,
        String apiSha256,
        String generatorVersion,
        String runtimeContractVersion,
        String bridgeContractVersion,
        List<FoundryClassDescriptor> classes) {
    public static final int CURRENT_FORMAT = 2;

    public FoundryModuleDescriptor {
        module = requireText(module, "module");
        registry = requireText(registry, "registry");
        apiSha256 = requireText(apiSha256, "apiSha256");
        generatorVersion = requireText(generatorVersion, "generatorVersion");
        runtimeContractVersion = requireText(runtimeContractVersion, "runtimeContractVersion");
        bridgeContractVersion = requireText(bridgeContractVersion, "bridgeContractVersion");
        classes = List.copyOf(Objects.requireNonNull(classes, "classes"));
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return checked;
    }
}
