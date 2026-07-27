package games.cafecito.foundry.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.cafecito.foundry.types.Variant;
import games.cafecito.foundry.types.VariantCodec;
import games.cafecito.foundry.types.VariantConversionException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    @Test
    void callableBackendsDistinguishLocalAndNativeValues() {
        FoundryCallable local =
                FoundryCallable.fixed(0, ignored -> Variant.of("local"));
        List<String> events = new ArrayList<>();
        FoundryCallable nativeValue =
                FoundryCallable.nativeBacked(
                        11,
                        41,
                        2,
                        new FoundryCallable.NativeBackend() {
                            @Override
                            public Variant invoke(
                                    long contextHandle,
                                    long bridgeHandle,
                                    List<Variant> arguments) {
                                events.add(
                                        "invoke:"
                                                + contextHandle
                                                + ":"
                                                + bridgeHandle
                                                + ":"
                                                + arguments.size());
                                return Variant.of("native");
                            }

                            @Override
                            public void release(long contextHandle, long bridgeHandle) {
                                events.add("release:" + contextHandle + ":" + bridgeHandle);
                            }
                        });

        assertTrue(local.isLocal());
        assertFalse(local.isNativeBacked());
        assertFalse(nativeValue.isLocal());
        assertTrue(nativeValue.isNativeBacked());
        assertEquals(11, nativeValue.nativeContextHandle());
        assertEquals(41, nativeValue.nativeBridgeHandle());
        assertEquals(2, nativeValue.arity());
        assertEquals(
                Variant.of("native"),
                nativeValue.call(List.of(Variant.nil(), Variant.nil())));
        nativeValue.close();
        nativeValue.close();
        assertEquals(List.of("invoke:11:41:2", "release:11:41"), events);
        assertThrows(
                IllegalStateException.class,
                () -> nativeValue.call(List.of(Variant.nil(), Variant.nil())));
    }

    @Test
    void nativeCallableDefersReleaseUntilAnActiveInvocationCompletes() throws Exception {
        CountDownLatch invocationStarted = new CountDownLatch(1);
        CountDownLatch allowInvocationToFinish = new CountDownLatch(1);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        FoundryCallable callable =
                FoundryCallable.nativeBacked(
                        11,
                        41,
                        0,
                        new FoundryCallable.NativeBackend() {
                            @Override
                            public Variant invoke(
                                    long contextHandle,
                                    long bridgeHandle,
                                    List<Variant> arguments) {
                                events.add("invoke:start");
                                invocationStarted.countDown();
                                try {
                                    assertTrue(
                                            allowInvocationToFinish.await(5, TimeUnit.SECONDS));
                                } catch (InterruptedException failure) {
                                    Thread.currentThread().interrupt();
                                    throw new AssertionError(failure);
                                }
                                events.add("invoke:end");
                                return Variant.nil();
                            }

                            @Override
                            public void release(long contextHandle, long bridgeHandle) {
                                events.add("release");
                            }
                        });
        Thread invocation = new Thread(() -> callable.call(List.of()));

        invocation.start();
        assertTrue(invocationStarted.await(5, TimeUnit.SECONDS));
        callable.close();
        assertTrue(callable.isClosed());
        assertTrue(events.equals(List.of("invoke:start")));

        allowInvocationToFinish.countDown();
        invocation.join(5_000);

        assertFalse(invocation.isAlive());
        assertEquals(List.of("invoke:start", "invoke:end", "release"), events);
    }

    @Test
    void signalBackendsKeepLocalBehaviorAndPreserveNativeIdentity() {
        FoundrySignal local = new FoundrySignal();
        List<String> events = new ArrayList<>();
        FoundrySignal nativeValue =
                FoundrySignal.nativeBacked(
                        12,
                        99,
                        new FoundrySignal.NativeBackend() {
                            @Override
                            public long connect(
                                    long contextHandle,
                                    long signalHandle,
                                    FoundryCallable callable) {
                                events.add("connect:" + contextHandle + ":" + signalHandle);
                                return 71;
                            }

                            @Override
                            public void disconnect(
                                    long contextHandle,
                                    long signalHandle,
                                    long connectionHandle) {
                                events.add(
                                        "disconnect:"
                                                + contextHandle
                                                + ":"
                                                + signalHandle
                                                + ":"
                                                + connectionHandle);
                            }

                            @Override
                            public void emit(
                                    long contextHandle,
                                    long signalHandle,
                                    List<Variant> arguments) {
                                events.add(
                                        "emit:"
                                                + contextHandle
                                                + ":"
                                                + signalHandle
                                                + ":"
                                                + arguments.size());
                            }

                            @Override
                            public void release(long contextHandle, long signalHandle) {
                                events.add("release:" + contextHandle + ":" + signalHandle);
                            }
                        });

        assertTrue(local.isLocal());
        assertFalse(local.isNativeBacked());
        assertFalse(nativeValue.isLocal());
        assertTrue(nativeValue.isNativeBacked());
        assertEquals(12, nativeValue.nativeContextHandle());
        assertEquals(99, nativeValue.nativeBridgeHandle());
        FoundrySignal.Connection connection =
                nativeValue.connect(FoundryCallable.variadic(ignored -> Variant.nil()));
        assertTrue(connection.isConnected());
        nativeValue.emit(Variant.nil());
        connection.disconnect();
        connection.disconnect();
        assertFalse(connection.isConnected());
        nativeValue.close();
        nativeValue.close();
        assertEquals(
                List.of(
                        "connect:12:99",
                        "emit:12:99:1",
                        "disconnect:12:99:71",
                        "release:12:99"),
                events);
        assertThrows(IllegalStateException.class, nativeValue::emit);
    }

    @Test
    void nativeSignalDoesNotHoldItsMonitorAcrossBackendCalls() throws Exception {
        CountDownLatch closeFinished = new CountDownLatch(1);
        Thread[] closer = new Thread[1];
        FoundrySignal[] signal = new FoundrySignal[1];
        signal[0] =
                FoundrySignal.nativeBacked(
                        12,
                        99,
                        new FoundrySignal.NativeBackend() {
                            @Override
                            public long connect(
                                    long contextHandle,
                                    long signalHandle,
                                    FoundryCallable callable) {
                                return 1;
                            }

                            @Override
                            public void disconnect(
                                    long contextHandle,
                                    long signalHandle,
                                    long connectionHandle) {}

                            @Override
                            public void emit(
                                    long contextHandle,
                                    long signalHandle,
                                    List<Variant> arguments) {
                                closer[0] =
                                        new Thread(
                                                () -> {
                                                    signal[0].close();
                                                    closeFinished.countDown();
                                                });
                                closer[0].start();
                                try {
                                    assertTrue(closeFinished.await(5, TimeUnit.SECONDS));
                                } catch (InterruptedException failure) {
                                    Thread.currentThread().interrupt();
                                    throw new AssertionError(failure);
                                }
                            }

                            @Override
                            public void release(long contextHandle, long signalHandle) {}
                        });

        signal[0].emit();
        closer[0].join(5_000);

        assertFalse(closer[0].isAlive());
        assertThrows(IllegalStateException.class, signal[0]::emit);
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
