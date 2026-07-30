package games.cafecito.foundry.acceptance;

import games.cafecito.foundry.annotations.FoundryClass;
import games.cafecito.foundry.annotations.FoundryInitialization;
import games.cafecito.foundry.annotations.FoundryMethod;
import games.cafecito.foundry.annotations.InitializationLevel;
import games.cafecito.foundry.generated.classes.Node;
import games.cafecito.foundry.runtime.FoundryBindingContext;
import games.cafecito.foundry.runtime.ObjectLease;

/**
 * The Java-defined engine class the engine-loaded conformance gate exercises.
 *
 * <p>This file exists twice, once under {@code src/registered/java} and once under {@code
 * src/unregistered/java}, and the two copies differ only in the registered engine class name. The
 * acceptance script resolves {@code FoundryJavaEngineProbe} through {@code ClassDB}, instantiates
 * it, and dispatches {@code engine_probe} into it, so the gate's runtime marker can only be produced
 * by a binding a real engine loaded and registered. Building the gate against the copy that
 * registers a different name leaves packaging, descriptor, registry index, keep rules, and bridge
 * ABIs unchanged and must still fail.
 */
@FoundryClass(base = Node.class, name = "FoundryJavaEngineProbe")
@FoundryInitialization(InitializationLevel.SCENE)
public final class EngineProbe extends Node {
    public EngineProbe(FoundryBindingContext context, ObjectLease lease) {
        super(context, lease);
    }

    @FoundryMethod(name = "engine_probe")
    public long engineProbe(long value) {
        return value + 1;
    }
}
