package games.cafecito.foundry.generator;

import games.cafecito.foundry.api.FoundryExtension;

/** Produces Java source stubs for the public extension ABI. */
public final class FoundrySourceGenerator {
    private FoundrySourceGenerator() {}

    public static String extensionTypeName() {
        return FoundryExtension.class.getName();
    }
}
