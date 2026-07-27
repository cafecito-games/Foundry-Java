package games.cafecito.foundry.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.cafecito.foundry.generated.classes.OpenXRExtensionWrapper;
import games.cafecito.foundry.generated.pointers.NativePointers;
import games.cafecito.foundry.types.Variant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FoundryNativeHandleTest {
    @Test
    void nativeHandlesAreContextBoundStronglyTypedAndExplicitlyNullable() {
        FoundryNativeHandle<TestStructure> handle =
                FoundryNativeHandle.of(7, TestStructure.class, 41);

        assertEquals(7, handle.contextHandle());
        assertEquals(TestStructure.class, handle.nativeType());
        assertEquals(41, handle.bridgeHandle());
        handle.requireContext(7);
        handle.requireType(TestStructure.class);
        assertThrows(IllegalArgumentException.class, () -> handle.requireContext(8));
        assertThrows(
                IllegalArgumentException.class, () -> handle.requireType(OtherStructure.class));

        FoundryNativeHandle<TestStructure> nullHandle =
                FoundryNativeHandle.nullHandle(7, TestStructure.class);
        assertTrue(nullHandle.isNull());
        assertEquals(0, nullHandle.bridgeHandle());
    }

    @Test
    void nativeHandlesRejectMissingTypeAndZeroContextBeforeTransport() {
        assertThrows(
                IllegalArgumentException.class,
                () -> FoundryNativeHandle.of(0, TestStructure.class, 41));
        assertThrows(
                NullPointerException.class,
                () -> FoundryNativeHandle.of(7, null, 41));
    }

    @Test
    void generatedPointerCallTransportsBridgeHandlesWithoutExposingAddresses() {
        CapturingEngine engine = new CapturingEngine();
        FoundryBindingContext context = new FoundryBindingContext(7, engine);
        TestOpenXrWrapper wrapper =
                new TestOpenXrWrapper(
                        context,
                        new ObjectLease(7, 11, ObjectOwnership.BORROWED, engine, context::isAlive));

        assertTrue(
                wrapper.eventPolled(FoundryNativeHandle.of(7, NativePointers.ConstVoid.class, 41)));
        assertEquals(41, engine.arguments.get(0).asLong());

        FoundryNativeHandle<NativePointers.ConstVoid> nullPointer =
                FoundryNativeHandle.nullHandle(7, NativePointers.ConstVoid.class);
        assertTrue(nullPointer.isNull());
        assertTrue(wrapper.eventPolled(nullPointer));
        assertEquals(0, engine.arguments.get(0).asLong());

        FoundryNativeHandle<NativePointers.ConstVoid> roundTrip =
                FoundryNativeHandle.of(
                        7, NativePointers.ConstVoid.class, engine.arguments.get(0).asLong());
        assertTrue(roundTrip.isNull());
        assertFalse(FoundryNativeHandle.of(7, NativePointers.ConstVoid.class, 41).isNull());
    }

    private static final class TestOpenXrWrapper extends OpenXRExtensionWrapper {
        private TestOpenXrWrapper(FoundryBindingContext context, ObjectLease lease) {
            super(context, lease);
        }

        private boolean eventPolled(FoundryNativeHandle<NativePointers.ConstVoid> event) {
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

    private static final class TestStructure {}

    private static final class OtherStructure {}
}
