package games.cafecito.foundry.test;

import games.cafecito.foundry.api.FoundryExtension;
import games.cafecito.foundry.runtime.FoundryRuntime;

/** Test helper for exercising a public extension without Android host dependencies. */
public final class FoundryExtensionTestSupport {
    private FoundryExtensionTestSupport() {}

    public static void attach(FoundryExtension extension) {
        FoundryRuntime.attach(extension);
    }
}
