package games.cafecito.foundry.samples.java;

import games.cafecito.foundry.annotations.FoundryClass;
import games.cafecito.foundry.annotations.FoundryInitialization;
import games.cafecito.foundry.annotations.FoundryMethod;
import games.cafecito.foundry.annotations.InitializationLevel;
import games.cafecito.foundry.generated.classes.Object;
import games.cafecito.foundry.runtime.FoundryBindingContext;
import games.cafecito.foundry.runtime.ObjectLease;

/**
 * A core-level sample extension class.
 *
 * <p>Registering one class at {@code CORE} and one at {@code SCENE} lets the conformance matrix
 * assert per-level registration and teardown ordering rather than a single-level shortcut.
 */
@FoundryClass(base = Object.class, name = "ConformanceCatalog")
@FoundryInitialization(InitializationLevel.CORE)
public final class ConformanceCatalog extends Object {
    private long lookupCount;

    public ConformanceCatalog(FoundryBindingContext context, ObjectLease lease) {
        super(context, lease);
    }

    public long lookupCount() {
        return lookupCount;
    }

    /** Exported extension method invoked by the engine through the generated trampoline. */
    @FoundryMethod
    public long lookup() {
        return ++lookupCount;
    }
}
