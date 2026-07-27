package games.cafecito.foundry.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.cafecito.foundry.runtime.FoundryCallError;
import games.cafecito.foundry.runtime.FoundryCallable;
import games.cafecito.foundry.runtime.FoundryClassDescriptor;
import games.cafecito.foundry.runtime.FoundryEngine;
import games.cafecito.foundry.runtime.FoundryNativeDispatch;
import games.cafecito.foundry.runtime.FoundrySignal;
import games.cafecito.foundry.types.FoundryDictionary;
import games.cafecito.foundry.types.Variant;
import games.cafecito.foundry.types.VariantCodec;
import games.cafecito.foundry.generated.GeneratedNativeDispatch;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FoundryNativeEngineTest {
    @Test
    void freezesTheTwelvePrivateStaticVersionedNativeDeclarations() {
        Map<String, Method> methods =
                Arrays.stream(FoundryNativeEngine.class.getDeclaredMethods())
                        .filter(method -> Modifier.isNative(method.getModifiers()))
                        .collect(Collectors.toMap(Method::getName, Function.identity()));

        assertEquals(
                Map.ofEntries(
                        signature(
                                "nativeCallV1",
                                FoundryEngine.CallResult.class,
                                long.class,
                                long.class,
                                FoundryNativeDispatch.class,
                                Variant[].class),
                        signature("nativeDecodeVariantV1", Variant.class, long.class, long.class),
                        signature(
                                "nativeEncodeVariantV1",
                                long.class,
                                long.class,
                                Variant.class),
                        signature(
                                "nativeIsObjectValidV1",
                                boolean.class,
                                long.class,
                                long.class),
                        signature(
                                "nativeObjectTypeV1",
                                String.class,
                                long.class,
                                long.class),
                        signature(
                                "nativeInstantiateV1",
                                long.class,
                                long.class,
                                String.class),
                        signature("nativeRetainV1", void.class, long.class, long.class),
                        signature("nativeReleaseV1", void.class, long.class, long.class),
                        signature(
                                "nativeSingletonV1",
                                long.class,
                                long.class,
                                String.class),
                        signature(
                                "nativeReportCallbackExceptionV1",
                                void.class,
                                long.class,
                                long.class,
                                Throwable.class),
                        signature(
                                "nativeRegisterExtensionClassV1",
                                void.class,
                                long.class,
                                FoundryClassDescriptor.class),
                        signature(
                                "nativeUnregisterExtensionClassV1",
                                void.class,
                                long.class,
                                String.class)),
                methods.entrySet().stream()
                        .collect(
                                Collectors.toMap(
                                        Map.Entry::getKey,
                                        entry -> methodSignature(entry.getValue()))));
        methods.values()
                .forEach(
                        method -> {
                            assertTrue(Modifier.isPrivate(method.getModifiers()), method.getName());
                            assertTrue(Modifier.isStatic(method.getModifiers()), method.getName());
                        });
    }

    @Test
    void productionConstructorUsesGeneratedLookupBeforeJni() {
        FoundryNativeEngine engine = new FoundryNativeEngine(11);

        IllegalArgumentException unknown =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> engine.call(11, 0, "missing/generated/identity", List.of()));

        assertTrue(unknown.getMessage().contains("Unknown Foundry native dispatch identity"));
    }

    @Test
    void rejectsUnknownIdentityBadArityAndWrongTypesBeforeGateway() {
        RecordingGateway gateway = new RecordingGateway();
        FoundryNativeEngine unknown =
                new FoundryNativeEngine(
                        11,
                        identity -> {
                            throw new IllegalArgumentException("Unknown native dispatch identity");
                        },
                        gateway);

        assertThrows(
                IllegalArgumentException.class,
                () -> unknown.call(11, 0, "missing", List.of()));
        assertEquals(0, gateway.calls);

        FoundryNativeEngine engine =
                new FoundryNativeEngine(11, ignored -> utility("int", 1, false), gateway);
        IllegalArgumentException arity =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> engine.call(11, 0, "utility_functions/demo#1", List.of()));
        assertTrue(arity.getMessage().contains("arity"));
        assertEquals(0, gateway.calls);

        IllegalArgumentException type =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                engine.call(
                                        11,
                                        0,
                                        "utility_functions/demo#1",
                                        List.of(Variant.of("wrong"))));
        assertTrue(type.getMessage().contains("expected int"));
        assertEquals(0, gateway.calls);
    }

    @Test
    void acceptsRealGeneratedNameOnlyPropertyAccessors() {
        RecordingGateway gateway = new RecordingGateway();
        FoundryNativeEngine engine =
                new FoundryNativeEngine(11, GeneratedNativeDispatch::require, gateway);
        String identity = "classes/BitMap/properties/data";

        engine.call(11, 71, identity, List.of());
        engine.call(
                11,
                71,
                identity,
                List.of(
                        Variant.of(
                                new FoundryDictionary<>(
                                        VariantCodec.VARIANT, VariantCodec.VARIANT))));

        assertEquals(List.of(identity, identity), gateway.dispatchIdentities);
    }

    @Test
    void validatesReceiverSeparatelyIncludingStaticBuiltins() {
        RecordingGateway gateway = new RecordingGateway();
        FoundryNativeDispatch dispatch =
                new FoundryNativeDispatch(
                        "builtin_classes/String/methods/num_scientific#2710373411",
                        FoundryNativeDispatch.Kind.BUILTIN_METHOD,
                        "String",
                        "num_scientific",
                        2710373411L,
                        -1,
                        List.of("float"),
                        1,
                        "String",
                        "",
                        "",
                        -1,
                        "",
                        "",
                        -1,
                        false,
                        true);
        FoundryNativeEngine engine = new FoundryNativeEngine(11, ignored -> dispatch, gateway);

        engine.call(11, 0, dispatch.identity(), List.of(Variant.of("unused"), Variant.of(2.0)));

        assertEquals(1, gateway.calls);
        IllegalArgumentException receiver =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                engine.call(
                                        11,
                                        0,
                                        dispatch.identity(),
                                        List.of(Variant.of(2L), Variant.of(2.0))));
        assertTrue(receiver.getMessage().contains("receiver"));
        assertEquals(1, gateway.calls);
    }

    @Test
    void rejectsLocalSignalsAndCrossContextNativeValuesBeforeEncoding() {
        RecordingGateway gateway = new RecordingGateway();
        FoundryNativeEngine engine =
                new FoundryNativeEngine(11, ignored -> utility("Variant", 0, true), gateway);

        IllegalArgumentException local =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> engine.encodeVariant(11, Variant.ofSignal(new FoundrySignal())));
        assertTrue(local.getMessage().contains("SIGNAL"));
        assertTrue(local.getMessage().contains("encode"));

        FoundrySignal foreign =
                FoundrySignal.nativeBacked(12, 77, new NoOpSignalBackend());
        IllegalArgumentException context =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> engine.encodeVariant(11, Variant.ofSignal(foreign)));
        assertTrue(context.getMessage().contains("context"));
        assertEquals(0, gateway.encodes);
    }

    @Test
    void delegatesEveryImplementedHostNeutralOperationToTheInjectedGateway() {
        RecordingGateway gateway = new RecordingGateway();
        FoundryNativeEngine engine =
                new FoundryNativeEngine(11, ignored -> utility("Variant", 0, true), gateway);
        Throwable failure = new IllegalStateException("boom");

        assertEquals(Variant.of("decoded"), engine.decodeVariant(11, 21));
        assertEquals(31, engine.encodeVariant(11, Variant.of("encoded")));
        assertTrue(engine.isObjectValid(11, 41));
        assertEquals("Node", engine.objectType(11, 41));
        assertEquals(51, engine.instantiate(11, "Node"));
        engine.retain(11, 41);
        engine.release(11, 41);
        assertEquals(61, engine.singleton(11, "Engine"));
        engine.reportCallbackException(11, 71, failure);

        assertEquals(
                List.of(
                        "decode:21",
                        "encode:STRING",
                        "valid:41",
                        "type:41",
                        "instantiate:Node",
                        "retain:41",
                        "release:41",
                        "singleton:Engine",
                        "report:71:boom"),
                gateway.events);
    }

    @Test
    void nativeCallableAndSignalFactoriesRouteThroughGeneratedGenericDispatch() {
        RecordingGateway gateway = new RecordingGateway();
        Map<String, FoundryNativeDispatch> dispatches =
                Map.of(
                        "builtin_classes/Callable/methods/call#3643564216",
                        builtinMethod("Callable", "call", 3643564216L, List.of(), 0, true, "Variant"),
                        "builtin_classes/Signal/methods/connect#979702392",
                        builtinMethod(
                                "Signal",
                                "connect",
                                979702392L,
                                List.of("Callable", "int"),
                                1,
                                false,
                                "int"),
                        "builtin_classes/Signal/methods/disconnect#3470848906",
                        builtinMethod(
                                "Signal",
                                "disconnect",
                                3470848906L,
                                List.of("Callable"),
                                1,
                                false,
                                "Nil"),
                        "builtin_classes/Signal/methods/emit#3286317445",
                        builtinMethod("Signal", "emit", 3286317445L, List.of(), 0, true, "Nil"));
        new FoundryNativeEngine(11, dispatches::get, gateway);
        FoundryCallable callable = FoundryNativeEngine.nativeCallableFromBridge(11, 81, 1);
        FoundrySignal signal = FoundryNativeEngine.nativeSignalFromBridge(11, 82);
        gateway.callResults.add(FoundryEngine.CallResult.success(Variant.of("called")));
        gateway.callResults.add(FoundryEngine.CallResult.success(Variant.of(0L)));
        gateway.callResults.add(FoundryEngine.CallResult.success(Variant.nil()));
        gateway.callResults.add(FoundryEngine.CallResult.success(Variant.nil()));

        assertEquals(Variant.of("called"), callable.call(List.of(Variant.of("value"))));
        FoundrySignal.Connection connection = signal.connect(callable);
        signal.emit(Variant.of("event"));
        connection.disconnect();
        callable.close();
        signal.close();

        assertEquals(
                List.of(
                        "builtin_classes/Callable/methods/call#3643564216",
                        "builtin_classes/Signal/methods/connect#979702392",
                        "builtin_classes/Signal/methods/emit#3286317445",
                        "builtin_classes/Signal/methods/disconnect#3470848906"),
                gateway.dispatchIdentities);
        assertEquals(List.of(81L, 82L), gateway.releasedHandles);
    }

    @Test
    void nativeSignalsRejectDuplicateCallableConnectionsBeforeDispatch() {
        RecordingGateway gateway = new RecordingGateway();
        FoundryNativeDispatch connect =
                builtinMethod(
                        "Signal",
                        "connect",
                        979702392L,
                        List.of("Callable", "int"),
                        1,
                        false,
                        "int");
        FoundryNativeDispatch disconnect =
                builtinMethod(
                        "Signal",
                        "disconnect",
                        3470848906L,
                        List.of("Callable"),
                        1,
                        false,
                        "Nil");
        Map<String, FoundryNativeDispatch> dispatches =
                Map.of(connect.identity(), connect, disconnect.identity(), disconnect);
        new FoundryNativeEngine(11, dispatches::get, gateway);
        FoundryCallable callable = FoundryNativeEngine.nativeCallableFromBridge(11, 81, 0);
        FoundrySignal signal = FoundryNativeEngine.nativeSignalFromBridge(11, 82);
        gateway.callResults.add(FoundryEngine.CallResult.success(Variant.of(0L)));

        FoundrySignal.Connection connection = signal.connect(callable);
        IllegalStateException duplicate =
                assertThrows(IllegalStateException.class, () -> signal.connect(callable));

        assertTrue(duplicate.getMessage().contains("already connected"));
        assertEquals(List.of(connect.identity()), gateway.dispatchIdentities);
        connection.disconnect();
        callable.close();
        signal.close();
    }

    @Test
    void failedNativeDisconnectRemainsConnectedForRetry() {
        RecordingGateway gateway = new RecordingGateway();
        Map<String, FoundryNativeDispatch> dispatches =
                Map.of(
                        "builtin_classes/Signal/methods/connect#979702392",
                        builtinMethod(
                                "Signal",
                                "connect",
                                979702392L,
                                List.of("Callable", "int"),
                                1,
                                false,
                                "int"),
                        "builtin_classes/Signal/methods/disconnect#3470848906",
                        builtinMethod(
                                "Signal",
                                "disconnect",
                                3470848906L,
                                List.of("Callable"),
                                1,
                                false,
                                "Nil"));
        new FoundryNativeEngine(11, dispatches::get, gateway);
        FoundryCallable callable = FoundryNativeEngine.nativeCallableFromBridge(11, 81, 0);
        FoundrySignal signal = FoundryNativeEngine.nativeSignalFromBridge(11, 82);
        gateway.callResults.add(FoundryEngine.CallResult.success(Variant.of(0L)));
        FoundrySignal.Connection connection = signal.connect(callable);
        gateway.callFailures.add(new IllegalStateException("disconnect failed"));

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, connection::disconnect);

        assertEquals("disconnect failed", failure.getMessage());
        assertTrue(connection.isConnected());
        connection.disconnect();
        assertFalse(connection.isConnected());
        callable.close();
        signal.close();
    }

    @Test
    void nativeSignalCloseAttemptsAllCleanupAndRetriesFailuresWithoutLeaking() {
        RecordingGateway gateway = new RecordingGateway();
        Map<String, FoundryNativeDispatch> dispatches =
                Map.of(
                        "builtin_classes/Signal/methods/connect#979702392",
                        builtinMethod(
                                "Signal",
                                "connect",
                                979702392L,
                                List.of("Callable", "int"),
                                1,
                                false,
                                "int"),
                        "builtin_classes/Signal/methods/disconnect#3470848906",
                        builtinMethod(
                                "Signal",
                                "disconnect",
                                3470848906L,
                                List.of("Callable"),
                                1,
                                false,
                                "Nil"));
        new FoundryNativeEngine(11, dispatches::get, gateway);
        FoundryCallable first = FoundryNativeEngine.nativeCallableFromBridge(11, 81, 0);
        FoundryCallable second = FoundryNativeEngine.nativeCallableFromBridge(11, 83, 0);
        FoundrySignal signal = FoundryNativeEngine.nativeSignalFromBridge(11, 82);
        gateway.callResults.add(FoundryEngine.CallResult.success(Variant.of(0L)));
        gateway.callResults.add(FoundryEngine.CallResult.success(Variant.of(0L)));
        signal.connect(first);
        signal.connect(second);
        gateway.callFailures.add(new IllegalStateException("disconnect failed"));
        gateway.releaseFailures.add(new IllegalStateException("release failed"));

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, signal::close);

        assertEquals("disconnect failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("release failed", failure.getSuppressed()[0].getMessage());

        signal.close();

        assertEquals(
                3,
                gateway.dispatchIdentities.stream()
                        .filter(
                                identity ->
                                        identity.equals(
                                                "builtin_classes/Signal/methods/disconnect#3470848906"))
                        .count());
        assertEquals(
                1, gateway.releasedHandles.stream().filter(handle -> handle == 82L).count());
        first.close();
        second.close();
    }

    @Test
    void closedNativeCallablesAreRejectedBeforeEncoding() {
        RecordingGateway gateway = new RecordingGateway();
        FoundryNativeEngine engine =
                new FoundryNativeEngine(11, ignored -> utility("Variant", 0, true), gateway);
        FoundryCallable callable = FoundryNativeEngine.nativeCallableFromBridge(11, 81, 0);
        callable.close();

        IllegalArgumentException closed =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> engine.encodeVariant(11, Variant.ofCallable(callable)));

        assertTrue(closed.getMessage().contains("closed"));
        assertEquals(0, gateway.encodes);
    }

    @Test
    void freezesRegistrationAsUnavailableBeforeTaskFiveWithoutGatewayMutation() {
        RecordingGateway gateway = new RecordingGateway();
        FoundryNativeEngine engine =
                new FoundryNativeEngine(11, ignored -> utility("Variant", 0, true), gateway);

        UnsupportedOperationException register =
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> engine.registerExtensionClass(11, null));
        UnsupportedOperationException unregister =
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> engine.unregisterExtensionClass(11, "Demo"));

        assertTrue(register.getMessage().contains("registration_unavailable_before_task5"));
        assertTrue(unregister.getMessage().contains("registration_unavailable_before_task5"));
        assertEquals(0, gateway.registrations);
    }

    @Test
    void rejectsEveryCrossContextOperationBeforeGateway() {
        RecordingGateway gateway = new RecordingGateway();
        FoundryNativeEngine engine =
                new FoundryNativeEngine(11, ignored -> utility("Variant", 0, true), gateway);

        assertThrows(IllegalArgumentException.class, () -> engine.decodeVariant(12, 1));
        assertThrows(IllegalArgumentException.class, () -> engine.isObjectValid(12, 1));
        assertThrows(IllegalArgumentException.class, () -> engine.singleton(12, "Engine"));

        assertTrue(gateway.events.isEmpty());
    }

    private static Map.Entry<String, String> signature(
            String name, Class<?> returnType, Class<?>... parameters) {
        return Map.entry(name, signatureText(returnType, parameters));
    }

    private static String methodSignature(Method method) {
        return signatureText(method.getReturnType(), method.getParameterTypes());
    }

    private static String signatureText(Class<?> returnType, Class<?>... parameters) {
        return Arrays.stream(parameters)
                        .map(Class::getTypeName)
                        .collect(Collectors.joining(",", "(", ")"))
                + returnType.getTypeName();
    }

    private static FoundryNativeDispatch utility(
            String argumentType, int minimumArgumentCount, boolean vararg) {
        List<String> arguments =
                argumentType.equals("Variant") && minimumArgumentCount == 0
                        ? List.of()
                        : List.of(argumentType);
        return new FoundryNativeDispatch(
                "utility_functions/demo#1",
                FoundryNativeDispatch.Kind.UTILITY_FUNCTION,
                "UtilityFunctions",
                "demo",
                1,
                -1,
                arguments,
                minimumArgumentCount,
                "Variant",
                "",
                "",
                -1,
                "",
                "",
                -1,
                vararg,
                true);
    }

    private static FoundryNativeDispatch builtinMethod(
            String owner,
            String name,
            long hash,
            List<String> arguments,
            int minimumArgumentCount,
            boolean vararg,
            String returnType) {
        return new FoundryNativeDispatch(
                "builtin_classes/" + owner + "/methods/" + name + "#" + hash,
                FoundryNativeDispatch.Kind.BUILTIN_METHOD,
                owner,
                name,
                hash,
                -1,
                arguments,
                minimumArgumentCount,
                returnType,
                "",
                "",
                -1,
                "",
                "",
                -1,
                vararg,
                false);
    }

    private static final class RecordingGateway implements FoundryNativeEngine.NativeGateway {
        private final java.util.ArrayList<String> events = new java.util.ArrayList<>();
        private final java.util.ArrayDeque<FoundryEngine.CallResult> callResults =
                new java.util.ArrayDeque<>();
        private final java.util.ArrayDeque<RuntimeException> callFailures =
                new java.util.ArrayDeque<>();
        private final java.util.ArrayDeque<RuntimeException> releaseFailures =
                new java.util.ArrayDeque<>();
        private final java.util.ArrayList<String> dispatchIdentities =
                new java.util.ArrayList<>();
        private final java.util.ArrayList<Long> releasedHandles = new java.util.ArrayList<>();
        private int calls;
        private int encodes;
        private int registrations;

        @Override
        public FoundryEngine.CallResult call(
                long contextHandle,
                long objectHandle,
                FoundryNativeDispatch dispatch,
                Variant[] arguments) {
            calls++;
            dispatchIdentities.add(dispatch.identity());
            if (!callFailures.isEmpty()) {
                throw callFailures.removeFirst();
            }
            return callResults.isEmpty()
                    ? FoundryEngine.CallResult.success(Variant.nil())
                    : callResults.removeFirst();
        }

        @Override
        public Variant decodeVariant(long contextHandle, long variantHandle) {
            events.add("decode:" + variantHandle);
            return Variant.of("decoded");
        }

        @Override
        public long encodeVariant(long contextHandle, Variant value) {
            encodes++;
            events.add("encode:" + value.type());
            return 31;
        }

        @Override
        public boolean isObjectValid(long contextHandle, long objectHandle) {
            events.add("valid:" + objectHandle);
            return true;
        }

        @Override
        public String objectType(long contextHandle, long objectHandle) {
            events.add("type:" + objectHandle);
            return "Node";
        }

        @Override
        public long instantiate(long contextHandle, String className) {
            events.add("instantiate:" + className);
            return 51;
        }

        @Override
        public void retain(long contextHandle, long objectHandle) {
            events.add("retain:" + objectHandle);
        }

        @Override
        public void release(long contextHandle, long objectHandle) {
            if (!releaseFailures.isEmpty()) {
                throw releaseFailures.removeFirst();
            }
            events.add("release:" + objectHandle);
            releasedHandles.add(objectHandle);
        }

        @Override
        public long singleton(long contextHandle, String name) {
            events.add("singleton:" + name);
            return 61;
        }

        @Override
        public void reportCallbackException(
                long contextHandle, long callbackHandle, Throwable failure) {
            events.add("report:" + callbackHandle + ":" + failure.getMessage());
        }

        @Override
        public void registerExtensionClass(
                long contextHandle, FoundryClassDescriptor descriptor) {
            registrations++;
        }

        @Override
        public void unregisterExtensionClass(long contextHandle, String foundryName) {
            registrations++;
        }
    }

    private static final class NoOpSignalBackend implements FoundrySignal.NativeBackend {
        @Override
        public long connect(
                long contextHandle, long signalHandle, FoundryCallable callable) {
            return 1;
        }

        @Override
        public void disconnect(
                long contextHandle, long signalHandle, long connectionHandle) {}

        @Override
        public void emit(long contextHandle, long signalHandle, List<Variant> arguments) {}

        @Override
        public void release(long contextHandle, long signalHandle) {}
    }
}
