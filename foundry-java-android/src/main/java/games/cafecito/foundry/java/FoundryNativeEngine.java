package games.cafecito.foundry.java;

import games.cafecito.foundry.runtime.FoundryCallError;
import games.cafecito.foundry.runtime.FoundryCallable;
import games.cafecito.foundry.runtime.FoundryClassDescriptor;
import games.cafecito.foundry.runtime.FoundryEngine;
import games.cafecito.foundry.runtime.FoundryNativeDispatch;
import games.cafecito.foundry.runtime.FoundrySignal;
import games.cafecito.foundry.types.Variant;
import games.cafecito.foundry.types.VariantType;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** Production FoundryEngine facade over the versioned JNI transport. */
public final class FoundryNativeEngine implements FoundryEngine {
    private static final String CALLABLE_CALL_IDENTITY =
            "builtin_classes/Callable/methods/call#3643564216";
    private static final String SIGNAL_CONNECT_IDENTITY =
            "builtin_classes/Signal/methods/connect#979702392";
    private static final String SIGNAL_DISCONNECT_IDENTITY =
            "builtin_classes/Signal/methods/disconnect#3470848906";
    private static final String SIGNAL_EMIT_IDENTITY =
            "builtin_classes/Signal/methods/emit#3286317445";
    private static final String REGISTRATION_UNAVAILABLE =
            "registration_unavailable_before_task5";
    private static final ConcurrentHashMap<Long, WeakReference<FoundryNativeEngine>> ENGINES =
            new ConcurrentHashMap<>();

    private final long contextHandle;
    private final Function<String, FoundryNativeDispatch> dispatchLookup;
    private final NativeGateway gateway;

    FoundryNativeEngine(
            long contextHandle,
            Function<String, FoundryNativeDispatch> dispatchLookup,
            NativeGateway gateway) {
        if (contextHandle == 0) {
            throw new IllegalArgumentException("Foundry context handle must be nonzero.");
        }
        this.contextHandle = contextHandle;
        this.dispatchLookup = Objects.requireNonNull(dispatchLookup, "dispatchLookup");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        ENGINES.put(contextHandle, new WeakReference<>(this));
    }

    @Override
    public void registerExtensionClass(
            long requestedContextHandle, FoundryClassDescriptor descriptor) {
        requireContext(requestedContextHandle);
        throw registrationUnavailable();
    }

    @Override
    public void unregisterExtensionClass(long requestedContextHandle, String foundryName) {
        requireContext(requestedContextHandle);
        throw registrationUnavailable();
    }

    @Override
    public CallResult call(
            long requestedContextHandle,
            long objectHandle,
            String methodIdentity,
            List<Variant> arguments) {
        requireContext(requestedContextHandle);
        String identity = requireText(methodIdentity, "methodIdentity");
        List<Variant> checkedArguments =
                List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        FoundryNativeDispatch dispatch;
        try {
            dispatch = dispatchLookup.apply(identity);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "Native dispatch lookup failed for " + identity + ".", failure);
        }
        if (dispatch == null) {
            throw new IllegalArgumentException("Unknown native dispatch identity: " + identity);
        }
        if (!dispatch.identity().equals(identity)) {
            throw new IllegalArgumentException(
                    "Native dispatch identity mismatch for "
                            + identity
                            + ": "
                            + dispatch.identity());
        }
        validateInvocation(dispatch, objectHandle, checkedArguments);
        return Objects.requireNonNull(
                gateway.call(
                        contextHandle,
                        objectHandle,
                        dispatch,
                        checkedArguments.toArray(Variant[]::new)),
                "native call result");
    }

    @Override
    public Variant decodeVariant(long requestedContextHandle, long variantHandle) {
        requireContext(requestedContextHandle);
        return Objects.requireNonNull(
                gateway.decodeVariant(contextHandle, variantHandle), "decoded Variant");
    }

    @Override
    public long encodeVariant(long requestedContextHandle, Variant value) {
        requireContext(requestedContextHandle);
        Variant checked = Objects.requireNonNull(value, "value");
        validateBridgeValue(checked, "encode");
        return gateway.encodeVariant(contextHandle, checked);
    }

    @Override
    public boolean isObjectValid(long requestedContextHandle, long objectHandle) {
        requireContext(requestedContextHandle);
        return gateway.isObjectValid(contextHandle, objectHandle);
    }

    @Override
    public String objectType(long requestedContextHandle, long objectHandle) {
        requireContext(requestedContextHandle);
        return Objects.requireNonNull(
                gateway.objectType(contextHandle, objectHandle), "native object type");
    }

    @Override
    public long instantiate(long requestedContextHandle, String className) {
        requireContext(requestedContextHandle);
        return gateway.instantiate(contextHandle, requireText(className, "className"));
    }

    @Override
    public void retain(long requestedContextHandle, long objectHandle) {
        requireContext(requestedContextHandle);
        gateway.retain(contextHandle, objectHandle);
    }

    @Override
    public void release(long requestedContextHandle, long objectHandle) {
        requireContext(requestedContextHandle);
        gateway.release(contextHandle, objectHandle);
    }

    @Override
    public long singleton(long requestedContextHandle, String name) {
        requireContext(requestedContextHandle);
        return gateway.singleton(contextHandle, requireText(name, "name"));
    }

    @Override
    public void reportCallbackException(
            long requestedContextHandle, long callbackHandle, Throwable failure) {
        requireContext(requestedContextHandle);
        gateway.reportCallbackException(
                contextHandle, callbackHandle, Objects.requireNonNull(failure, "failure"));
    }

    static FoundryCallable nativeCallableFromBridge(
            long contextHandle, long bridgeHandle, int arity) {
        FoundryNativeEngine engine = requireEngine(contextHandle);
        FoundryCallable[] callable = new FoundryCallable[1];
        callable[0] =
                FoundryCallable.nativeBacked(
                        contextHandle,
                        bridgeHandle,
                        arity,
                        new FoundryCallable.NativeBackend() {
                            @Override
                            public Variant invoke(
                                    long requestedContext,
                                    long requestedHandle,
                                    List<Variant> arguments) {
                                ArrayList<Variant> callArguments =
                                        new ArrayList<>(arguments.size() + 1);
                                callArguments.add(Variant.ofCallable(callable[0]));
                                callArguments.addAll(arguments);
                                return engine.callValue(
                                        requestedContext,
                                        CALLABLE_CALL_IDENTITY,
                                        callArguments,
                                        "native_callable_invoke");
                            }

                            @Override
                            public void release(long requestedContext, long requestedHandle) {
                                engine.release(requestedContext, requestedHandle);
                            }
                        });
        return callable[0];
    }

    static FoundrySignal nativeSignalFromBridge(long contextHandle, long bridgeHandle) {
        FoundryNativeEngine engine = requireEngine(contextHandle);
        FoundrySignal[] signal = new FoundrySignal[1];
        signal[0] =
                FoundrySignal.nativeBacked(
                        contextHandle,
                        bridgeHandle,
                        engine.new SignalBackend(signal, bridgeHandle));
        return signal[0];
    }

    private Variant callValue(
            long requestedContext,
            String identity,
            List<Variant> arguments,
            String phase) {
        CallResult result = call(requestedContext, 0, identity, arguments);
        if (result.error() != FoundryCallError.OK) {
            throw new IllegalStateException(
                    phase
                            + " failed with "
                            + result.error()
                            + " at argument "
                            + result.argumentIndex()
                            + " expected "
                            + result.expectedType()
                            + ".");
        }
        return Objects.requireNonNull(result.value(), phase + " result");
    }

    private void validateInvocation(
            FoundryNativeDispatch dispatch, long objectHandle, List<Variant> arguments) {
        int receiverCount = receiverBearing(dispatch.kind()) ? 1 : 0;
        validateObjectHandle(dispatch, objectHandle);
        if (arguments.size() < receiverCount) {
            throw arityFailure(dispatch, arguments.size(), receiverCount);
        }
        if (receiverCount == 1) {
            validateType(dispatch, arguments.get(0), dispatch.ownerNativeType(), "receiver");
        }

        int formalCount = arguments.size() - receiverCount;
        if (dispatch.kind() == FoundryNativeDispatch.Kind.CLASS_PROPERTY) {
            boolean validGetter = formalCount == 0 && !dispatch.getterNativeName().isEmpty();
            boolean validSetter = formalCount == 1 && !dispatch.setterNativeName().isEmpty();
            if (!validGetter && !validSetter) {
                throw arityFailure(dispatch, formalCount, receiverCount);
            }
        } else {
            int maximum = dispatch.argumentNativeTypes().size();
            boolean valid =
                    formalCount >= dispatch.minimumArgumentCount()
                            && (dispatch.vararg() || formalCount <= maximum);
            if (!valid) {
                throw arityFailure(dispatch, formalCount, receiverCount);
            }
        }

        int checkedFormalCount =
                Math.min(formalCount, dispatch.argumentNativeTypes().size());
        for (int index = 0; index < checkedFormalCount; index++) {
            validateType(
                    dispatch,
                    arguments.get(index + receiverCount),
                    dispatch.argumentNativeTypes().get(index),
                    "argument " + index);
        }
        for (Variant argument : arguments) {
            validateBridgeValue(argument, "dispatch");
        }
    }

    private static void validateObjectHandle(
            FoundryNativeDispatch dispatch, long objectHandle) {
        boolean instanceObject =
                (dispatch.kind() == FoundryNativeDispatch.Kind.CLASS_METHOD
                                && !dispatch.staticCall())
                        || dispatch.kind() == FoundryNativeDispatch.Kind.CLASS_PROPERTY
                        || dispatch.kind() == FoundryNativeDispatch.Kind.CLASS_SIGNAL;
        if (instanceObject == (objectHandle == 0)) {
            throw new IllegalArgumentException(
                    "Native dispatch "
                            + dispatch.identity()
                            + (instanceObject
                                    ? " requires a nonzero object handle."
                                    : " requires a zero object handle."));
        }
    }

    private void validateType(
            FoundryNativeDispatch dispatch,
            Variant value,
            String nativeType,
            String position) {
        if (!matchesNativeType(value.type(), nativeType)) {
            throw new IllegalArgumentException(
                    "Native dispatch "
                            + dispatch.identity()
                            + " "
                            + position
                            + " expected "
                            + nativeType
                            + " but received "
                            + value.type()
                            + ".");
        }
    }

    private void validateBridgeValue(Variant value, String phase) {
        if (value.type() == VariantType.CALLABLE) {
            FoundryCallable callable = value.asCallable();
            if (callable.isClosed()) {
                throw new IllegalArgumentException(
                        "closed CALLABLE values are unsupported during " + phase + ".");
            }
            if (callable.isNativeBacked()
                    && callable.nativeContextHandle() != contextHandle) {
                throw new IllegalArgumentException(
                        "CALLABLE context mismatch during " + phase + ".");
            }
        } else if (value.type() == VariantType.SIGNAL) {
            FoundrySignal signal = value.asSignal();
            if (signal.isLocal()) {
                throw new IllegalArgumentException(
                        "Local SIGNAL values are unsupported during " + phase + ".");
            }
            if (signal.nativeContextHandle() != contextHandle) {
                throw new IllegalArgumentException(
                        "SIGNAL context mismatch during " + phase + ".");
            }
        }
    }

    private static boolean matchesNativeType(VariantType actual, String nativeType) {
        if (nativeType.equals("Variant")) {
            return true;
        }
        if (nativeType.startsWith("enum::") || nativeType.startsWith("bitfield::")) {
            return actual == VariantType.INTEGER;
        }
        if (nativeType.startsWith("typedarray::")) {
            return actual == VariantType.ARRAY;
        }
        if (nativeType.startsWith("typeddictionary::")) {
            return actual == VariantType.DICTIONARY;
        }
        if (nativeType.endsWith("*")) {
            return actual == VariantType.INTEGER;
        }
        return switch (nativeType) {
            case "void", "Nil" -> actual == VariantType.NIL;
            case "bool" -> actual == VariantType.BOOLEAN;
            case "int" -> actual == VariantType.INTEGER;
            case "float" -> actual == VariantType.FLOAT;
            case "String" -> actual == VariantType.STRING;
            case "Vector2" -> actual == VariantType.VECTOR2;
            case "Vector2i" -> actual == VariantType.VECTOR2I;
            case "Rect2" -> actual == VariantType.RECT2;
            case "Rect2i" -> actual == VariantType.RECT2I;
            case "Vector3" -> actual == VariantType.VECTOR3;
            case "Vector3i" -> actual == VariantType.VECTOR3I;
            case "Transform2D" -> actual == VariantType.TRANSFORM2D;
            case "Vector4" -> actual == VariantType.VECTOR4;
            case "Vector4i" -> actual == VariantType.VECTOR4I;
            case "Plane" -> actual == VariantType.PLANE;
            case "Quaternion" -> actual == VariantType.QUATERNION;
            case "AABB" -> actual == VariantType.AABB;
            case "Basis" -> actual == VariantType.BASIS;
            case "Transform3D" -> actual == VariantType.TRANSFORM3D;
            case "Projection" -> actual == VariantType.PROJECTION;
            case "Color" -> actual == VariantType.COLOR;
            case "StringName" -> actual == VariantType.STRING_NAME;
            case "NodePath" -> actual == VariantType.NODE_PATH;
            case "RID" -> actual == VariantType.RID;
            case "Object" -> actual == VariantType.OBJECT;
            case "Callable" -> actual == VariantType.CALLABLE;
            case "Signal" -> actual == VariantType.SIGNAL;
            case "Dictionary" -> actual == VariantType.DICTIONARY;
            case "Array" -> actual == VariantType.ARRAY;
            case "PackedByteArray" -> actual == VariantType.PACKED_BYTE_ARRAY;
            case "PackedInt32Array" -> actual == VariantType.PACKED_INT32_ARRAY;
            case "PackedInt64Array" -> actual == VariantType.PACKED_INT64_ARRAY;
            case "PackedFloat32Array" -> actual == VariantType.PACKED_FLOAT32_ARRAY;
            case "PackedFloat64Array" -> actual == VariantType.PACKED_FLOAT64_ARRAY;
            case "PackedStringArray" -> actual == VariantType.PACKED_STRING_ARRAY;
            case "PackedVector2Array" -> actual == VariantType.PACKED_VECTOR2_ARRAY;
            case "PackedVector3Array" -> actual == VariantType.PACKED_VECTOR3_ARRAY;
            case "PackedColorArray" -> actual == VariantType.PACKED_COLOR_ARRAY;
            case "PackedVector4Array" -> actual == VariantType.PACKED_VECTOR4_ARRAY;
            default -> actual == VariantType.OBJECT;
        };
    }

    private static boolean receiverBearing(FoundryNativeDispatch.Kind kind) {
        return kind == FoundryNativeDispatch.Kind.BUILTIN_METHOD
                || kind == FoundryNativeDispatch.Kind.BUILTIN_OPERATOR
                || kind == FoundryNativeDispatch.Kind.BUILTIN_MEMBER;
    }

    private static IllegalArgumentException arityFailure(
            FoundryNativeDispatch dispatch, int actual, int receiverCount) {
        return new IllegalArgumentException(
                "Native dispatch "
                        + dispatch.identity()
                        + " rejected arity "
                        + actual
                        + " after "
                        + receiverCount
                        + " receiver values; required "
                        + dispatch.minimumArgumentCount()
                        + ".."
                        + (dispatch.vararg()
                                ? "*"
                                : Integer.toString(dispatch.argumentNativeTypes().size()))
                        + ".");
    }

    private void requireContext(long requestedContextHandle) {
        if (requestedContextHandle != contextHandle) {
            throw new IllegalArgumentException(
                    "Foundry engine belongs to context "
                            + contextHandle
                            + ", not "
                            + requestedContextHandle
                            + ".");
        }
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return checked;
    }

    private static FoundryNativeEngine requireEngine(long contextHandle) {
        WeakReference<FoundryNativeEngine> reference = ENGINES.get(contextHandle);
        FoundryNativeEngine engine = reference == null ? null : reference.get();
        if (engine == null) {
            ENGINES.remove(contextHandle, reference);
            throw new IllegalStateException(
                    "No live Foundry native engine exists for context " + contextHandle + ".");
        }
        return engine;
    }

    private static UnsupportedOperationException registrationUnavailable() {
        return new UnsupportedOperationException(REGISTRATION_UNAVAILABLE);
    }

    private final class SignalBackend implements FoundrySignal.NativeBackend {
        private final FoundrySignal[] signal;
        private final long signalHandle;
        private final Object connectionLock = new Object();
        private final AtomicLong nextConnection = new AtomicLong();
        private final Map<Long, FoundryCallable> connections = new LinkedHashMap<>();
        private final IdentityHashMap<FoundryCallable, Long> connectionsByCallable =
                new IdentityHashMap<>();
        private final Set<FoundryCallable> pendingConnections =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean signalReleased;

        private SignalBackend(FoundrySignal[] signal, long signalHandle) {
            this.signal = signal;
            this.signalHandle = signalHandle;
        }

        @Override
        public long connect(
                long requestedContext,
                long requestedSignalHandle,
                FoundryCallable callable) {
            requireSignal(requestedSignalHandle);
            synchronized (connectionLock) {
                if (signalReleased) {
                    throw new IllegalStateException("Native Signal is released.");
                }
                if (connectionsByCallable.containsKey(callable)
                        || !pendingConnections.add(callable)) {
                    throw new IllegalStateException(
                            "Callable is already connected to this native Signal.");
                }
            }
            boolean connected = false;
            try {
                long error =
                        callValue(
                                        requestedContext,
                                        SIGNAL_CONNECT_IDENTITY,
                                        List.of(
                                                Variant.ofSignal(signal[0]),
                                                Variant.ofCallable(callable)),
                                        "native_signal_connect")
                                .asLong();
                if (error != 0) {
                    throw new IllegalStateException(
                            "native_signal_connect failed with error " + error + ".");
                }
                long connection = nextConnection.incrementAndGet();
                synchronized (connectionLock) {
                    pendingConnections.remove(callable);
                    connections.put(connection, callable);
                    connectionsByCallable.put(callable, connection);
                }
                connected = true;
                return connection;
            } finally {
                if (!connected) {
                    synchronized (connectionLock) {
                        pendingConnections.remove(callable);
                    }
                }
            }
        }

        @Override
        public void disconnect(
                long requestedContext,
                long requestedSignalHandle,
                long connectionHandle) {
            requireSignal(requestedSignalHandle);
            FoundryCallable callable;
            synchronized (connectionLock) {
                callable = connections.get(connectionHandle);
                if (callable == null) {
                    return;
                }
            }
            callValue(
                    requestedContext,
                    SIGNAL_DISCONNECT_IDENTITY,
                    List.of(Variant.ofSignal(signal[0]), Variant.ofCallable(callable)),
                    "native_signal_disconnect");
            synchronized (connectionLock) {
                if (connections.get(connectionHandle) == callable) {
                    connections.remove(connectionHandle);
                    connectionsByCallable.remove(callable);
                }
            }
        }

        @Override
        public void emit(
                long requestedContext,
                long requestedSignalHandle,
                List<Variant> arguments) {
            requireSignal(requestedSignalHandle);
            ArrayList<Variant> signalArguments = new ArrayList<>(arguments.size() + 1);
            signalArguments.add(Variant.ofSignal(signal[0]));
            signalArguments.addAll(arguments);
            callValue(
                    requestedContext,
                    SIGNAL_EMIT_IDENTITY,
                    signalArguments,
                    "native_signal_emit");
        }

        @Override
        public void release(long requestedContext, long requestedSignalHandle) {
            requireSignal(requestedSignalHandle);
            List<Long> activeConnections;
            synchronized (connectionLock) {
                if (signalReleased) {
                    connections.clear();
                    connectionsByCallable.clear();
                    pendingConnections.clear();
                    return;
                }
                activeConnections = List.copyOf(connections.keySet());
            }
            Throwable failure = null;
            for (Long connection : activeConnections) {
                try {
                    disconnect(requestedContext, requestedSignalHandle, connection);
                } catch (RuntimeException | Error disconnectFailure) {
                    failure = combineFailures(failure, disconnectFailure);
                }
            }
            try {
                FoundryNativeEngine.this.release(requestedContext, requestedSignalHandle);
                synchronized (connectionLock) {
                    signalReleased = true;
                    connections.clear();
                    connectionsByCallable.clear();
                    pendingConnections.clear();
                }
            } catch (RuntimeException | Error releaseFailure) {
                failure = combineFailures(failure, releaseFailure);
            }
            if (failure != null) {
                rethrowUnchecked(failure);
            }
        }

        private void requireSignal(long requestedSignalHandle) {
            if (requestedSignalHandle != signalHandle) {
                throw new IllegalArgumentException("Native Signal handle mismatch.");
            }
        }
    }

    private static Throwable combineFailures(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static void rethrowUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        throw (Error) failure;
    }

    interface NativeGateway {
        CallResult call(
                long contextHandle,
                long objectHandle,
                FoundryNativeDispatch dispatch,
                Variant[] arguments);

        Variant decodeVariant(long contextHandle, long variantHandle);

        long encodeVariant(long contextHandle, Variant value);

        boolean isObjectValid(long contextHandle, long objectHandle);

        String objectType(long contextHandle, long objectHandle);

        long instantiate(long contextHandle, String className);

        void retain(long contextHandle, long objectHandle);

        void release(long contextHandle, long objectHandle);

        long singleton(long contextHandle, String name);

        void reportCallbackException(
                long contextHandle, long callbackHandle, Throwable failure);

        void registerExtensionClass(
                long contextHandle, FoundryClassDescriptor descriptor);

        void unregisterExtensionClass(long contextHandle, String foundryName);
    }

    private static final class JniNativeGateway implements NativeGateway {
        @Override
        public CallResult call(
                long contextHandle,
                long objectHandle,
                FoundryNativeDispatch dispatch,
                Variant[] arguments) {
            return nativeCallV1(contextHandle, objectHandle, dispatch, arguments);
        }

        @Override
        public Variant decodeVariant(long contextHandle, long variantHandle) {
            return nativeDecodeVariantV1(contextHandle, variantHandle);
        }

        @Override
        public long encodeVariant(long contextHandle, Variant value) {
            return nativeEncodeVariantV1(contextHandle, value);
        }

        @Override
        public boolean isObjectValid(long contextHandle, long objectHandle) {
            return nativeIsObjectValidV1(contextHandle, objectHandle);
        }

        @Override
        public String objectType(long contextHandle, long objectHandle) {
            return nativeObjectTypeV1(contextHandle, objectHandle);
        }

        @Override
        public long instantiate(long contextHandle, String className) {
            return nativeInstantiateV1(contextHandle, className);
        }

        @Override
        public void retain(long contextHandle, long objectHandle) {
            nativeRetainV1(contextHandle, objectHandle);
        }

        @Override
        public void release(long contextHandle, long objectHandle) {
            nativeReleaseV1(contextHandle, objectHandle);
        }

        @Override
        public long singleton(long contextHandle, String name) {
            return nativeSingletonV1(contextHandle, name);
        }

        @Override
        public void reportCallbackException(
                long contextHandle, long callbackHandle, Throwable failure) {
            nativeReportCallbackExceptionV1(contextHandle, callbackHandle, failure);
        }

        @Override
        public void registerExtensionClass(
                long contextHandle, FoundryClassDescriptor descriptor) {
            nativeRegisterExtensionClassV1(contextHandle, descriptor);
        }

        @Override
        public void unregisterExtensionClass(long contextHandle, String foundryName) {
            nativeUnregisterExtensionClassV1(contextHandle, foundryName);
        }
    }

    private static native CallResult nativeCallV1(
            long contextHandle,
            long objectHandle,
            FoundryNativeDispatch dispatch,
            Variant[] arguments);

    private static native Variant nativeDecodeVariantV1(long contextHandle, long variantHandle);

    private static native long nativeEncodeVariantV1(long contextHandle, Variant value);

    private static native boolean nativeIsObjectValidV1(long contextHandle, long objectHandle);

    private static native String nativeObjectTypeV1(long contextHandle, long objectHandle);

    private static native long nativeInstantiateV1(long contextHandle, String className);

    private static native void nativeRetainV1(long contextHandle, long objectHandle);

    private static native void nativeReleaseV1(long contextHandle, long objectHandle);

    private static native long nativeSingletonV1(long contextHandle, String name);

    private static native void nativeReportCallbackExceptionV1(
            long contextHandle, long callbackHandle, Throwable failure);

    private static native void nativeRegisterExtensionClassV1(
            long contextHandle, FoundryClassDescriptor descriptor);

    private static native void nativeUnregisterExtensionClassV1(
            long contextHandle, String foundryName);
}
