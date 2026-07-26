package games.cafecito.foundry.build;

import games.cafecito.foundry.api.model.FoundryApi;
import games.cafecito.foundry.api.model.FoundryApiParser;
import games.cafecito.foundry.generator.FoundrySourceGenerator;

/** Compile-only proof that generator consumers receive the public API model transitively. */
public final class GeneratorOnlyConsumer {
    private GeneratorOnlyConsumer() {}

    public static FoundryApi parse(String json) {
        FoundrySourceGenerator.extensionTypeName();
        return FoundryApiParser.parse(json);
    }
}
