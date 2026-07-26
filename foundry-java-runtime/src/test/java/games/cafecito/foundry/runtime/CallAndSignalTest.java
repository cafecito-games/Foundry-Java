package games.cafecito.foundry.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.cafecito.foundry.types.Variant;
import games.cafecito.foundry.types.VariantCodec;
import games.cafecito.foundry.types.VariantConversionException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CallAndSignalTest {
    @Test
    void engineCallErrorsPreserveAllDiagnosticFields() {
        ErrorEngine engine = new ErrorEngine();
        engine.valid = true;
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        CallableObject object =
                context.bind(
                        7, ObjectOwnership.BORROWED, CallableObject.class, CallableObject::new);

        FoundryCallException error =
                assertThrows(
                        FoundryCallException.class,
                        () -> object.invoke("Node3D::set_position", Variant.of("bad")));

        assertEquals("Node3D::set_position", error.methodIdentity());
        assertEquals(FoundryCallError.INVALID_ARGUMENT, error.callError());
        assertEquals(0, error.argumentIndex());
        assertEquals("Vector3", error.expectedType());
        assertTrue(error.getMessage().contains("Node3D::set_position"));

        FoundryCallException staticError =
                assertThrows(
                        FoundryCallException.class,
                        () ->
                                context.call(
                                        0,
                                        "UtilityFunctions::type_convert",
                                        List.of(Variant.of("bad"))));
        assertEquals("UtilityFunctions::type_convert", staticError.methodIdentity());
        assertEquals(FoundryCallError.INVALID_ARGUMENT, staticError.callError());
    }

    @Test
    void typedCallablesEnforceArityAndConversionAtTheBoundary() {
        FoundryCallable upper =
                FoundryCallable.unary(
                        VariantCodec.STRING, VariantCodec.STRING, String::toUpperCase);

        assertEquals(Variant.of("PLAYER"), upper.call(List.of(Variant.of("player"))));
        IllegalArgumentException arity =
                assertThrows(IllegalArgumentException.class, () -> upper.call(List.of()));
        assertTrue(arity.getMessage().contains("expected 1"));
        assertTrue(arity.getMessage().contains("received 0"));
        assertThrows(VariantConversionException.class, () -> upper.call(List.of(Variant.of(1L))));
    }

    @Test
    void signalsUseConnectionOrderAndSnapshotReentrantMutations() {
        FoundrySignal signal = new FoundrySignal();
        List<String> events = new ArrayList<>();
        FoundrySignal.Connection[] second = new FoundrySignal.Connection[1];
        FoundrySignal.Connection first =
                signal.connect(
                        FoundryCallable.variadic(
                                arguments -> {
                                    events.add("first");
                                    second[0].disconnect();
                                    signal.connect(
                                            FoundryCallable.variadic(
                                                    ignored -> {
                                                        events.add("third");
                                                        return Variant.nil();
                                                    }));
                                    return Variant.nil();
                                }));
        second[0] =
                signal.connect(
                        FoundryCallable.variadic(
                                arguments -> {
                                    events.add("second");
                                    return Variant.nil();
                                }));

        signal.emit();
        signal.emit();
        first.disconnect();
        first.disconnect();
        signal.emit();

        assertEquals(List.of("first", "second", "first", "third", "third", "third"), events);
        assertFalse(first.isConnected());
    }

    @Test
    void signalsPermitSameThreadReentrancy() {
        FoundrySignal signal = new FoundrySignal();
        List<Integer> depths = new ArrayList<>();
        signal.connect(
                FoundryCallable.variadic(
                        arguments -> {
                            int depth = arguments.get(0).asInt();
                            depths.add(depth);
                            if (depth < 2) {
                                signal.emit(Variant.of((long) depth + 1));
                            }
                            return Variant.nil();
                        }));

        signal.emit(Variant.of(0L));

        assertEquals(List.of(0, 1, 2), depths);
    }

    static final class CallableObject extends FoundryObject {
        CallableObject(FoundryBindingContext context, ObjectLease lease) {
            super(context, lease);
        }

        Variant invoke(String methodIdentity, Variant... arguments) {
            return call(methodIdentity, arguments);
        }
    }

    static final class ErrorEngine extends NoOpEngine {
        boolean valid;

        @Override
        public boolean isObjectValid(long contextHandle, long objectHandle) {
            return valid;
        }

        @Override
        public CallResult call(
                long contextHandle,
                long objectHandle,
                String methodIdentity,
                List<Variant> arguments) {
            return new CallResult(Variant.nil(), FoundryCallError.INVALID_ARGUMENT, 0, "Vector3");
        }
    }
}
