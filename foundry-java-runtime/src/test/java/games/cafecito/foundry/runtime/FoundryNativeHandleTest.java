package games.cafecito.foundry.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.cafecito.foundry.generated.classes.OpenXRExtensionWrapper;
import games.cafecito.foundry.types.Variant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FoundryNativeHandleTest {
    @Test
    void generatedPointerCallTransportsBridgeHandlesWithoutExposingAddresses() {
        CapturingEngine engine = new CapturingEngine();
        FoundryBindingContext context = new FoundryBindingContext(7, engine);
        TestOpenXrWrapper wrapper =
                new TestOpenXrWrapper(
                        context,
                        new ObjectLease(7, 11, ObjectOwnership.BORROWED, engine, context::isAlive));

        assertTrue(wrapper.eventPolled(new FoundryNativeHandle("const void*", 41)));
        assertEquals(41, engine.arguments.get(0).asLong());

        FoundryNativeHandle nullPointer = FoundryNativeHandle.nullHandle("const void*");
        assertTrue(nullPointer.isNull());
        assertTrue(wrapper.eventPolled(nullPointer));
        assertEquals(0, engine.arguments.get(0).asLong());

        FoundryNativeHandle roundTrip =
                new FoundryNativeHandle("const void*", engine.arguments.get(0).asLong());
        assertTrue(roundTrip.isNull());
        assertFalse(new FoundryNativeHandle("const void*", 41).isNull());
    }

    private static final class TestOpenXrWrapper extends OpenXRExtensionWrapper {
        private TestOpenXrWrapper(FoundryBindingContext context, ObjectLease lease) {
            super(context, lease);
        }

        private boolean eventPolled(FoundryNativeHandle event) {
            return onOnEventPolled(event);
        }
    }

    private static final class CapturingEngine extends NoOpEngine {
        private List<Variant> arguments = List.of();

        @Override
        public CallResult call(
                long contextHandle,
                long objectHandle,
                String methodIdentity,
                List<Variant> arguments) {
            this.arguments = List.copyOf(arguments);
            return CallResult.success(Variant.of(true));
        }
    }
}
