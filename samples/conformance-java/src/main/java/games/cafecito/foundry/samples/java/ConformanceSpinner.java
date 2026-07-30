package games.cafecito.foundry.samples.java;

import games.cafecito.foundry.annotations.FoundryClass;
import games.cafecito.foundry.annotations.FoundryConstant;
import games.cafecito.foundry.annotations.FoundryInitialization;
import games.cafecito.foundry.annotations.FoundryMethod;
import games.cafecito.foundry.annotations.FoundryOverride;
import games.cafecito.foundry.annotations.FoundryProperty;
import games.cafecito.foundry.annotations.FoundrySignal;
import games.cafecito.foundry.annotations.InitializationLevel;
import games.cafecito.foundry.generated.classes.Node;
import games.cafecito.foundry.runtime.FoundryBindingContext;
import games.cafecito.foundry.runtime.ObjectLease;

/** A scene-level sample extension class exposing every authored member kind. */
@FoundryClass(base = Node.class, name = "ConformanceSpinner")
@FoundryInitialization(InitializationLevel.SCENE)
public final class ConformanceSpinner extends Node {
    /** Exported integral constant grouped into a Foundry enum. */
    @FoundryConstant(enumName = "SpinDirection")
    public static final long SPIN_DIRECTION_CLOCKWISE = 1L;

    /** Exported integral constant grouped into a Foundry enum. */
    @FoundryConstant(enumName = "SpinDirection")
    public static final long SPIN_DIRECTION_COUNTERCLOCKWISE = -1L;

    @FoundryProperty(getter = "speed", setter = "speed")
    private double speed;

    private double accumulatedDelta;
    private long resetCount;

    public ConformanceSpinner(FoundryBindingContext context, ObjectLease lease) {
        super(context, lease);
    }

    /** Exported signal signature carrying the new speed. */
    @FoundrySignal
    public interface SpeedChanged {
        void emitted(double speed);
    }

    public double speed() {
        return speed;
    }

    public void speed(double value) {
        speed = value;
    }

    public double accumulatedDelta() {
        return accumulatedDelta;
    }

    public long resetCount() {
        return resetCount;
    }

    /** Exported extension method invoked by the engine through the generated trampoline. */
    @FoundryMethod
    public void reset() {
        speed = 0.0;
        accumulatedDelta = 0.0;
        resetCount++;
    }

    /** Exported extension method returning an engine object argument unchanged. */
    @FoundryMethod
    public Node echo(Node value) {
        return value;
    }

    /** Direct Java implementation of the generated {@code _process} virtual. */
    @FoundryOverride
    public void onProcess(double delta) {
        accumulatedDelta += delta;
    }
}
