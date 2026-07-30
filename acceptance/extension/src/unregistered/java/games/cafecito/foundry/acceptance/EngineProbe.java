package games.cafecito.foundry.acceptance;

import games.cafecito.foundry.annotations.FoundryClass;
import games.cafecito.foundry.annotations.FoundryInitialization;
import games.cafecito.foundry.annotations.FoundryMethod;
import games.cafecito.foundry.annotations.FoundryOverride;
import games.cafecito.foundry.annotations.FoundryProperty;
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
 *
 * <p>The class exposes one member of every kind a real engine reaches by a different path: an
 * exported method the script calls directly, a virtual override the engine itself invokes when the
 * node enters the tree, and a property the script round-trips through the engine's property system.
 * An exported method alone leaves the virtual dispatch key unproven, because a virtual is resolved
 * by its exported Foundry name and then dispatched by the Java name, and nothing else in the gate
 * makes a real engine take that path.
 */
@FoundryClass(base = Node.class, name = "FoundryJavaEngineProbeDisabled")
@FoundryInitialization(InitializationLevel.SCENE)
public final class EngineProbe extends Node {
    @FoundryProperty(name = "probe_scale", getter = "probeScale", setter = "probeScale")
    private long probeScale;

    private long readyDispatchCount;

    public EngineProbe(FoundryBindingContext context, ObjectLease lease) {
        super(context, lease);
    }

    public long probeScale() {
        return probeScale;
    }

    public void probeScale(long value) {
        probeScale = value;
    }

    @FoundryMethod(name = "engine_probe")
    public long engineProbe(long value) {
        return value + 1;
    }

    /**
     * Reports how many times the engine dispatched the {@code _ready} virtual into Java. The script
     * reads this back rather than treating the absence of a crash as evidence, so a virtual that is
     * registered but never dispatched still fails the gate.
     */
    @FoundryMethod(name = "ready_dispatch_count")
    public long readyDispatchCount() {
        return readyDispatchCount;
    }

    /** Direct Java implementation of the generated {@code _ready} virtual. */
    @FoundryOverride
    public void onReady() {
        readyDispatchCount++;
    }
}
