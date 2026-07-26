package games.cafecito.foundry.runtime;

import games.cafecito.foundry.api.FoundryExtension;
import java.util.Objects;

/** Host-neutral Java runtime entry point for the public extension ABI. */
public final class FoundryRuntime {
    private FoundryRuntime() {}

    /** Invokes the public lifecycle hook without depending on an Android host type. */
    public static void attach(FoundryExtension extension) {
        Objects.requireNonNull(extension, "extension").onAttached();
    }
}
