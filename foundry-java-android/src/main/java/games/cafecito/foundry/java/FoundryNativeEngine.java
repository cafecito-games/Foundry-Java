package games.cafecito.foundry.java;

import games.cafecito.foundry.generated.GeneratedNativeDispatch;
import games.cafecito.foundry.generated.GeneratedRegistration;
import games.cafecito.foundry.runtime.FoundryBindingContext;
import games.cafecito.foundry.runtime.FoundryBindingContextAware;
import games.cafecito.foundry.runtime.FoundryCallError;
import games.cafecito.foundry.runtime.FoundryCallable;
import games.cafecito.foundry.runtime.FoundryClassDescriptor;
import games.cafecito.foundry.runtime.FoundryEngine;
import games.cafecito.foundry.runtime.FoundryExtensionAccess;
import games.cafecito.foundry.runtime.FoundryMemberDescriptor;
import games.cafecito.foundry.runtime.FoundryMemberDetails;
import games.cafecito.foundry.runtime.FoundryNativeDispatch;
import games.cafecito.foundry.runtime.FoundryObject;
import games.cafecito.foundry.runtime.FoundrySignal;
import games.cafecito.foundry.runtime.ObjectLease;
import games.cafecito.foundry.runtime.ObjectOwnership;
import games.cafecito.foundry.types.Aabb;
import games.cafecito.foundry.types.Basis;
import games.cafecito.foundry.types.Color;
import games.cafecito.foundry.types.FoundryArray;
import games.cafecito.foundry.types.FoundryDictionary;
import games.cafecito.foundry.types.NodePath;
import games.cafecito.foundry.types.PackedByteArray;
import games.cafecito.foundry.types.PackedColorArray;
import games.cafecito.foundry.types.PackedFloat32Array;
import games.cafecito.foundry.types.PackedFloat64Array;
import games.cafecito.foundry.types.PackedInt32Array;
import games.cafecito.foundry.types.PackedInt64Array;
import games.cafecito.foundry.types.PackedStringArray;
import games.cafecito.foundry.types.PackedVector2Array;
import games.cafecito.foundry.types.PackedVector3Array;
import games.cafecito.foundry.types.PackedVector4Array;
import games.cafecito.foundry.types.Plane;
import games.cafecito.foundry.types.Projection;
import games.cafecito.foundry.types.Quaternion;
import games.cafecito.foundry.types.Rect2;
import games.cafecito.foundry.types.Rect2i;
import games.cafecito.foundry.types.Rid;
import games.cafecito.foundry.types.StringName;
import games.cafecito.foundry.types.Transform2D;
import games.cafecito.foundry.types.Transform3D;
import games.cafecito.foundry.types.Variant;
import games.cafecito.foundry.types.VariantCodec;
import games.cafecito.foundry.types.VariantType;
import games.cafecito.foundry.types.Vector2;
import games.cafecito.foundry.types.Vector2i;
import games.cafecito.foundry.types.Vector3;
import games.cafecito.foundry.types.Vector3i;
import games.cafecito.foundry.types.Vector4;
import games.cafecito.foundry.types.Vector4i;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/** Production FoundryEngine facade over the versioned JNI transport. */
public final class FoundryNativeEngine implements FoundryEngine, FoundryBindingContextAware {
    private static final String CALLABLE_CALL_IDENTITY =
            "builtin_classes/Callable/methods/call#3643564216";
    private static final String SIGNAL_CONNECT_IDENTITY =
            "builtin_classes/Signal/methods/connect#979702392";
    private static final String SIGNAL_DISCONNECT_IDENTITY =
            "builtin_classes/Signal/methods/disconnect#3470848906";
    private static final String SIGNAL_EMIT_IDENTITY =
            "builtin_classes/Signal/methods/emit#3286317445";
    private static final ConcurrentHashMap<Long, WeakReference<FoundryNativeEngine>> ENGINES =
            new ConcurrentHashMap<>();
    private static final ThreadLocal<FoundryNativeEngine> ACTIVE_REGISTRATION_ENGINE =
            new ThreadLocal<>();
    private static final AtomicLong NEXT_LOCAL_CALLABLE_ID = new AtomicLong();
    private static final Map<FoundryCallable, Long> LOCAL_CALLABLE_IDS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final long contextHandle;
    private final Function<String, FoundryNativeDispatch> dispatchLookup;
    private final NativeGateway gateway;
    private final Consumer<FoundryBindingContext> generatedRegistration;
    private final ThreadLocal<FoundrySignal> allowedClosedSignal = new ThreadLocal<>();
    private final ThreadLocal<FoundryCallable> allowedClosedCallable = new ThreadLocal<>();
    private volatile WeakReference<FoundryBindingContext> bindingContext =
            new WeakReference<>(null);

    public FoundryNativeEngine(long contextHandle) {
        this(
                contextHandle,
                GeneratedNativeDispatch::require,
                new JniNativeGateway(),
                GeneratedRegistration::registerAll);
    }

    FoundryNativeEngine(
            long contextHandle,
            Function<String, FoundryNativeDispatch> dispatchLookup,
            NativeGateway gateway) {
        this(contextHandle, dispatchLookup, gateway, GeneratedRegistration::registerAll);
    }

    FoundryNativeEngine(
            long contextHandle,
            Function<String, FoundryNativeDispatch> dispatchLookup,
            NativeGateway gateway,
            Consumer<FoundryBindingContext> generatedRegistration) {
        if (contextHandle == 0) {
            throw new IllegalArgumentException("Foundry context handle must be nonzero.");
        }
        this.contextHandle = contextHandle;
        this.dispatchLookup = Objects.requireNonNull(dispatchLookup, "dispatchLookup");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.generatedRegistration =
                Objects.requireNonNull(generatedRegistration, "generatedRegistration");
        ENGINES.compute(
                contextHandle,
                (ignored, existingReference) -> {
                    FoundryNativeEngine existing =
                            existingReference == null ? null : existingReference.get();
                    FoundryBindingContext existingContext =
                            existing == null ? null : existing.bindingContext.get();
                    if (existing != null
                            && existing != this
                            && existingContext != null
                            && existingContext.isAlive()) {
                        throw new IllegalStateException(
                                "Foundry context "
                                        + contextHandle
                                        + " already has a live native engine.");
                    }
                    return new WeakReference<>(this);
                });
    }

    @Override
    public synchronized void attachBindingContext(FoundryBindingContext context) {
        FoundryBindingContext checked = Objects.requireNonNull(context, "context");
        requireContext(checked.contextHandle());
        FoundryBindingContext existing = bindingContext.get();
        if (existing == checked) {
            return;
        }
        if (existing != null && existing.isAlive()) {
            throw new IllegalStateException(
                    "Foundry context " + contextHandle + " already has a live binding context.");
        }
        generatedRegistration.accept(checked);
        bindingContext = new WeakReference<>(checked);
    }

    @Override
    public void registerExtensionClass(
            long requestedContextHandle, FoundryClassDescriptor descriptor) {
        requireContext(requestedContextHandle);
        FoundryClassDescriptor checked = Objects.requireNonNull(descriptor, "descriptor");
        FoundryNativeEngine outerEngine = ACTIVE_REGISTRATION_ENGINE.get();
        ACTIVE_REGISTRATION_ENGINE.set(this);
        try {
            gateway.registerExtensionClass(contextHandle, checked);
        } finally {
            if (outerEngine == null) {
                ACTIVE_REGISTRATION_ENGINE.remove();
            } else {
                ACTIVE_REGISTRATION_ENGINE.set(outerEngine);
            }
        }
    }

    @Override
    public void unregisterExtensionClass(long requestedContextHandle, String foundryName) {
        requireContext(requestedContextHandle);
        String checkedName = requireText(foundryName, "foundryName");
        gateway.unregisterExtensionClass(contextHandle, checkedName);
    }

    @Override
    public CallResult call(
            long requestedContextHandle,
            long objectHandle,
            String methodIdentity,
            List<Variant> arguments) {
        return call(requestedContextHandle, objectHandle, methodIdentity, arguments, null);
    }

    private CallResult call(
            long requestedContextHandle,
            long objectHandle,
            String methodIdentity,
            List<Variant> arguments,
            FoundrySignal allowedClosedSignal) {
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
        validateInvocation(dispatch, objectHandle, checkedArguments, allowedClosedSignal);
        FoundrySignal previousAllowedSignal = this.allowedClosedSignal.get();
        if (allowedClosedSignal == null) {
            this.allowedClosedSignal.remove();
        } else {
            this.allowedClosedSignal.set(allowedClosedSignal);
        }
        try {
            return Objects.requireNonNull(
                    gateway.call(
                            contextHandle,
                            objectHandle,
                            dispatch,
                            checkedArguments.toArray(Variant[]::new)),
                    "native call result");
        } finally {
            if (previousAllowedSignal == null) {
                this.allowedClosedSignal.remove();
            } else {
                this.allowedClosedSignal.set(previousAllowedSignal);
            }
        }
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

    static FoundryCallable nativeCallableFromBridge(long contextHandle, long bridgeHandle) {
        return nativeCallableFromBridge(contextHandle, bridgeHandle, -1);
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

    static Rid nativeRidFromBridge(long contextHandle, long bridgeHandle) {
        FoundryNativeEngine engine = requireEngine(contextHandle);
        return Rid.nativeBacked(
                contextHandle,
                bridgeHandle,
                (requestedContext, requestedHandle) ->
                        engine.release(requestedContext, requestedHandle));
    }

    static games.cafecito.foundry.runtime.FoundryObject nativeObjectFromBridge(
            long contextHandle, long objectHandle) {
        FoundryNativeEngine engine = requireEngine(contextHandle);
        FoundryBindingContext context = engine.bindingContext.get();
        if (context == null || !context.isAlive()) {
            throw new IllegalStateException("native_object_binding_context_unavailable");
        }
        return context.bind(
                objectHandle,
                ObjectOwnership.BORROWED,
                FoundryObject.class,
                NativeDecodedObject::new);
    }

    private static final class NativeDecodedObject extends FoundryObject {
        private NativeDecodedObject(FoundryBindingContext context, ObjectLease lease) {
            super(context, lease);
        }
    }

    private Variant callValue(
            long requestedContext, String identity, List<Variant> arguments, String phase) {
        return callValue(requestedContext, identity, arguments, phase, null);
    }

    private Variant callValue(
            long requestedContext,
            String identity,
            List<Variant> arguments,
            String phase,
            FoundrySignal allowedClosedSignal) {
        CallResult result = call(requestedContext, 0, identity, arguments, allowedClosedSignal);
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
            FoundryNativeDispatch dispatch,
            long objectHandle,
            List<Variant> arguments,
            FoundrySignal allowedClosedSignal) {
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

        int checkedFormalCount = Math.min(formalCount, dispatch.argumentNativeTypes().size());
        for (int index = 0; index < checkedFormalCount; index++) {
            validateType(
                    dispatch,
                    arguments.get(index + receiverCount),
                    dispatch.argumentNativeTypes().get(index),
                    "argument " + index);
        }
        for (Variant argument : arguments) {
            validateBridgeValue(argument, "dispatch", allowedClosedSignal);
        }
    }

    private static void validateObjectHandle(FoundryNativeDispatch dispatch, long objectHandle) {
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
            FoundryNativeDispatch dispatch, Variant value, String nativeType, String position) {
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
        validateBridgeValue(value, phase, null);
    }

    private void validateBridgeValue(
            Variant value, String phase, FoundrySignal allowedClosedSignal) {
        Set<Object> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        validateBridgeValue(value, phase, allowedClosedSignal, visiting, visited);
    }

    private void validateBridgeValue(
            Variant value,
            String phase,
            FoundrySignal allowedClosedSignal,
            Set<Object> visiting,
            Set<Object> visited) {
        if (value.type() == VariantType.RID) {
            Rid rid = value.asRid();
            if (rid.isClosed()) {
                throw new IllegalArgumentException(
                        "closed RID values are unsupported during " + phase + ".");
            }
            if (!rid.isNativeBacked() && rid.id() != 0) {
                throw new IllegalArgumentException(
                        "nonzero local RID values are unsupported during " + phase + ".");
            }
            if (rid.isNativeBacked() && rid.nativeContextHandle() != contextHandle) {
                throw new IllegalArgumentException("RID context mismatch during " + phase + ".");
            }
        } else if (value.type() == VariantType.CALLABLE) {
            FoundryCallable callable = value.asCallable();
            if (callable.isClosed() && callable != allowedClosedCallable.get()) {
                throw new IllegalArgumentException(
                        "closed CALLABLE values are unsupported during " + phase + ".");
            }
            if (callable.isNativeBacked() && callable.nativeContextHandle() != contextHandle) {
                throw new IllegalArgumentException(
                        "CALLABLE context mismatch during " + phase + ".");
            }
        } else if (value.type() == VariantType.SIGNAL) {
            FoundrySignal signal = value.asSignal();
            if (signal.isClosed() && signal != allowedClosedSignal) {
                throw new IllegalArgumentException(
                        "closed SIGNAL values are unsupported during " + phase + ".");
            }
            if (signal.isLocal()) {
                throw new IllegalArgumentException(
                        "Local SIGNAL values are unsupported during " + phase + ".");
            }
            if (signal.nativeContextHandle() != contextHandle) {
                throw new IllegalArgumentException("SIGNAL context mismatch during " + phase + ".");
            }
        } else if (value.type() == VariantType.OBJECT) {
            if (value.asObject().context().contextHandle() != contextHandle) {
                throw new IllegalArgumentException("OBJECT context mismatch during " + phase + ".");
            }
        } else if (value.type() == VariantType.ARRAY) {
            Object identity = value.value();
            beginCollectionValidation(identity, phase, visiting, visited);
            if (!visited.contains(identity)) {
                for (Variant element : ((FoundryArray<?>) identity).variantSnapshot()) {
                    validateBridgeValue(element, phase, allowedClosedSignal, visiting, visited);
                }
                finishCollectionValidation(identity, visiting, visited);
            }
        } else if (value.type() == VariantType.DICTIONARY) {
            Object identity = value.value();
            beginCollectionValidation(identity, phase, visiting, visited);
            if (!visited.contains(identity)) {
                FoundryDictionary.VariantSnapshot snapshot =
                        ((FoundryDictionary<?, ?>) identity).variantSnapshot();
                Variant[] keys = snapshot.keys();
                Variant[] values = snapshot.values();
                for (int index = 0; index < keys.length; index++) {
                    validateBridgeValue(keys[index], phase, allowedClosedSignal, visiting, visited);
                    validateBridgeValue(
                            values[index], phase, allowedClosedSignal, visiting, visited);
                }
                finishCollectionValidation(identity, visiting, visited);
            }
        }
    }

    private static void beginCollectionValidation(
            Object identity, String phase, Set<Object> visiting, Set<Object> visited) {
        if (visited.contains(identity)) {
            return;
        }
        if (!visiting.add(identity)) {
            throw new IllegalArgumentException(
                    "cyclic Variant collection is unsupported during " + phase + ".");
        }
    }

    private static void finishCollectionValidation(
            Object identity, Set<Object> visiting, Set<Object> visited) {
        visiting.remove(identity);
        visited.add(identity);
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

    private final class SignalBackend implements FoundrySignal.NativeBackend {
        private final FoundrySignal[] signal;
        private final long signalHandle;
        private final Object connectionLock = new Object();
        private final AtomicLong nextConnection = new AtomicLong();
        private final Map<Long, ConnectedCallable> connections = new LinkedHashMap<>();
        private final IdentityHashMap<FoundryCallable, Long> connectionsByCallable =
                new IdentityHashMap<>();
        private final Set<FoundryCallable> pendingConnections =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<FoundryCallable> pendingCallableCleanup =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean signalReleased;

        private SignalBackend(FoundrySignal[] signal, long signalHandle) {
            this.signal = signal;
            this.signalHandle = signalHandle;
        }

        @Override
        public long connect(
                long requestedContext, long requestedSignalHandle, FoundryCallable callable) {
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
            FoundryCallable transportCallable = callable;
            boolean ownsTransportCallable = false;
            try {
                if (callable.isNativeBacked()) {
                    FoundryNativeEngine.this.retain(
                            callable.nativeContextHandle(), callable.nativeBridgeHandle());
                    transportCallable =
                            nativeCallableFromBridge(
                                    callable.nativeContextHandle(),
                                    callable.nativeBridgeHandle(),
                                    callable.arity());
                    ownsTransportCallable = true;
                }
                long error =
                        callValue(
                                        requestedContext,
                                        SIGNAL_CONNECT_IDENTITY,
                                        List.of(
                                                Variant.ofSignal(signal[0]),
                                                Variant.ofCallable(transportCallable)),
                                        "native_signal_connect")
                                .asLong();
                if (error != 0) {
                    throw new IllegalStateException(
                            "native_signal_connect failed with error " + error + ".");
                }
                long connection = nextConnection.incrementAndGet();
                synchronized (connectionLock) {
                    pendingConnections.remove(callable);
                    connections.put(
                            connection,
                            new ConnectedCallable(
                                    callable, transportCallable, ownsTransportCallable));
                    connectionsByCallable.put(callable, connection);
                }
                connected = true;
                return connection;
            } catch (RuntimeException | Error connectFailure) {
                if (ownsTransportCallable) {
                    try {
                        transportCallable.close();
                    } catch (RuntimeException | Error cleanupFailure) {
                        synchronized (connectionLock) {
                            pendingCallableCleanup.add(transportCallable);
                        }
                        connectFailure.addSuppressed(cleanupFailure);
                    }
                }
                throw connectFailure;
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
                long requestedContext, long requestedSignalHandle, long connectionHandle) {
            requireSignal(requestedSignalHandle);
            ConnectedCallable connection;
            boolean interrupted = false;
            boolean disconnectNative = false;
            synchronized (connectionLock) {
                connection = connections.get(connectionHandle);
                if (connection == null) {
                    return;
                }
                while (connection.nativeDisconnecting) {
                    try {
                        connectionLock.wait();
                    } catch (InterruptedException interruption) {
                        interrupted = true;
                    }
                }
                if (!connection.nativeDisconnected) {
                    connection.nativeDisconnecting = true;
                    disconnectNative = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (disconnectNative) {
                FoundryCallable previousAllowedCallable = allowedClosedCallable.get();
                allowedClosedCallable.set(connection.transportCallable);
                try {
                    callValue(
                            requestedContext,
                            SIGNAL_DISCONNECT_IDENTITY,
                            List.of(
                                    Variant.ofSignal(signal[0]),
                                    Variant.ofCallable(connection.transportCallable)),
                            "native_signal_disconnect",
                            signal[0]);
                    synchronized (connectionLock) {
                        connection.nativeDisconnected = true;
                        connection.nativeDisconnecting = false;
                        connectionLock.notifyAll();
                    }
                } catch (RuntimeException | Error disconnectFailure) {
                    synchronized (connectionLock) {
                        connection.nativeDisconnecting = false;
                        connectionLock.notifyAll();
                    }
                    throw disconnectFailure;
                } finally {
                    if (previousAllowedCallable == null) {
                        allowedClosedCallable.remove();
                    } else {
                        allowedClosedCallable.set(previousAllowedCallable);
                    }
                }
            }
            if (connection.ownsTransportCallable) {
                connection.transportCallable.close();
            }
            synchronized (connectionLock) {
                if (connections.get(connectionHandle) == connection) {
                    connections.remove(connectionHandle);
                    connectionsByCallable.remove(connection.originalCallable);
                }
            }
        }

        @Override
        public void emit(
                long requestedContext, long requestedSignalHandle, List<Variant> arguments) {
            requireSignal(requestedSignalHandle);
            ArrayList<Variant> signalArguments = new ArrayList<>(arguments.size() + 1);
            signalArguments.add(Variant.ofSignal(signal[0]));
            signalArguments.addAll(arguments);
            callValue(
                    requestedContext, SIGNAL_EMIT_IDENTITY, signalArguments, "native_signal_emit");
        }

        @Override
        public void release(long requestedContext, long requestedSignalHandle) {
            requireSignal(requestedSignalHandle);
            List<Long> activeConnections;
            List<FoundryCallable> pendingCleanup;
            synchronized (connectionLock) {
                if (signalReleased) {
                    connections.clear();
                    connectionsByCallable.clear();
                    pendingConnections.clear();
                    return;
                }
                activeConnections = List.copyOf(connections.keySet());
                pendingCleanup = List.copyOf(pendingCallableCleanup);
            }
            Throwable failure = null;
            for (FoundryCallable callable : pendingCleanup) {
                try {
                    callable.close();
                    synchronized (connectionLock) {
                        pendingCallableCleanup.remove(callable);
                    }
                } catch (RuntimeException | Error cleanupFailure) {
                    failure = combineFailures(failure, cleanupFailure);
                }
            }
            for (Long connection : activeConnections) {
                try {
                    disconnect(requestedContext, requestedSignalHandle, connection);
                } catch (RuntimeException | Error disconnectFailure) {
                    failure = combineFailures(failure, disconnectFailure);
                }
            }
            if (failure != null) {
                rethrowUnchecked(failure);
            }
            try {
                FoundryNativeEngine.this.release(requestedContext, requestedSignalHandle);
                synchronized (connectionLock) {
                    signalReleased = true;
                    connections.clear();
                    connectionsByCallable.clear();
                    pendingConnections.clear();
                    pendingCallableCleanup.clear();
                }
            } catch (RuntimeException | Error releaseFailure) {
                rethrowUnchecked(releaseFailure);
            }
        }

        private void requireSignal(long requestedSignalHandle) {
            if (requestedSignalHandle != signalHandle) {
                throw new IllegalArgumentException("Native Signal handle mismatch.");
            }
        }

        private final class ConnectedCallable {
            private final FoundryCallable originalCallable;
            private final FoundryCallable transportCallable;
            private final boolean ownsTransportCallable;
            private boolean nativeDisconnected;
            private boolean nativeDisconnecting;

            private ConnectedCallable(
                    FoundryCallable originalCallable,
                    FoundryCallable transportCallable,
                    boolean ownsTransportCallable) {
                this.originalCallable = originalCallable;
                this.transportCallable = transportCallable;
                this.ownsTransportCallable = ownsTransportCallable;
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

    private static NativeVariantSnapshot nativeSnapshotV1(long contextHandle, Variant variant) {
        FoundryNativeEngine engine = requireEngine(contextHandle);
        Variant frozen =
                engine.freezeBridgeValue(
                        Objects.requireNonNull(variant, "variant"),
                        "native snapshot",
                        engine.allowedClosedSignal.get(),
                        Collections.newSetFromMap(new IdentityHashMap<>()),
                        new IdentityHashMap<>());
        return nativeSnapshotUncheckedV1(frozen);
    }

    private Variant freezeBridgeValue(
            Variant value,
            String phase,
            FoundrySignal allowedClosedSignal,
            Set<Object> visiting,
            IdentityHashMap<Object, Variant> frozenCollections) {
        if (value.type() != VariantType.ARRAY && value.type() != VariantType.DICTIONARY) {
            validateBridgeValue(
                    value,
                    phase,
                    allowedClosedSignal,
                    Collections.newSetFromMap(new IdentityHashMap<>()),
                    Collections.newSetFromMap(new IdentityHashMap<>()));
            return value;
        }
        Object identity = value.value();
        Variant frozen = frozenCollections.get(identity);
        if (frozen != null) {
            return frozen;
        }
        if (!visiting.add(identity)) {
            throw new IllegalArgumentException(
                    "cyclic Variant collection is unsupported during " + phase + ".");
        }
        try {
            if (value.type() == VariantType.ARRAY) {
                FoundryArray<Variant> copy = FoundryArray.untyped();
                for (Variant element : ((FoundryArray<?>) identity).variantSnapshot()) {
                    copy.addVariant(
                            freezeBridgeValue(
                                    element,
                                    phase,
                                    allowedClosedSignal,
                                    visiting,
                                    frozenCollections));
                }
                frozen = Variant.of(copy);
            } else {
                FoundryDictionary<Variant, Variant> copy =
                        new FoundryDictionary<>(VariantCodec.VARIANT, VariantCodec.VARIANT);
                FoundryDictionary.VariantSnapshot snapshot =
                        ((FoundryDictionary<?, ?>) identity).variantSnapshot();
                Variant[] keys = snapshot.keys();
                Variant[] values = snapshot.values();
                for (int index = 0; index < keys.length; index++) {
                    copy.putVariants(
                            freezeBridgeValue(
                                    keys[index],
                                    phase,
                                    allowedClosedSignal,
                                    visiting,
                                    frozenCollections),
                            freezeBridgeValue(
                                    values[index],
                                    phase,
                                    allowedClosedSignal,
                                    visiting,
                                    frozenCollections));
                }
                frozen = Variant.of(copy);
            }
            frozenCollections.put(identity, frozen);
            return frozen;
        } finally {
            visiting.remove(identity);
        }
    }

    private static NativeVariantSnapshot nativeSnapshotUncheckedV1(Variant variant) {
        Variant value = Objects.requireNonNull(variant, "variant");
        return switch (value.type()) {
            case NIL -> NativeVariantSnapshot.empty(value.type());
            case BOOLEAN ->
                    NativeVariantSnapshot.integers(
                            value.type(), new long[] {value.asBoolean() ? 1 : 0});
            case INTEGER ->
                    NativeVariantSnapshot.integers(value.type(), new long[] {value.asLong()});
            case FLOAT ->
                    NativeVariantSnapshot.reals(value.type(), new double[] {value.asDouble()});
            case STRING -> NativeVariantSnapshot.text(value.type(), value.asString());
            case VECTOR2 -> NativeVariantSnapshot.reals(value.type(), vector2(value.asVector2()));
            case VECTOR2I ->
                    NativeVariantSnapshot.integers(value.type(), vector2i(value.asVector2i()));
            case RECT2 -> {
                Rect2 rect = value.asRect2();
                yield NativeVariantSnapshot.reals(
                        value.type(), concat(vector2(rect.position()), vector2(rect.size())));
            }
            case RECT2I -> {
                Rect2i rect = value.asRect2i();
                yield NativeVariantSnapshot.integers(
                        value.type(), concat(vector2i(rect.position()), vector2i(rect.size())));
            }
            case VECTOR3 -> NativeVariantSnapshot.reals(value.type(), vector3(value.asVector3()));
            case VECTOR3I ->
                    NativeVariantSnapshot.integers(value.type(), vector3i(value.asVector3i()));
            case TRANSFORM2D -> {
                Transform2D transform = value.asTransform2D();
                yield NativeVariantSnapshot.reals(
                        value.type(),
                        concat(
                                vector2(transform.x()),
                                vector2(transform.y()),
                                vector2(transform.origin())));
            }
            case VECTOR4 -> NativeVariantSnapshot.reals(value.type(), vector4(value.asVector4()));
            case VECTOR4I ->
                    NativeVariantSnapshot.integers(value.type(), vector4i(value.asVector4i()));
            case PLANE -> {
                Plane plane = value.asPlane();
                yield NativeVariantSnapshot.reals(
                        value.type(), concat(vector3(plane.normal()), new double[] {plane.d()}));
            }
            case QUATERNION ->
                    NativeVariantSnapshot.reals(value.type(), quaternion(value.asQuaternion()));
            case AABB -> {
                Aabb aabb = value.asAabb();
                yield NativeVariantSnapshot.reals(
                        value.type(), concat(vector3(aabb.position()), vector3(aabb.size())));
            }
            case BASIS -> {
                Basis basis = value.asBasis();
                yield NativeVariantSnapshot.reals(
                        value.type(),
                        concat(vector3(basis.x()), vector3(basis.y()), vector3(basis.z())));
            }
            case TRANSFORM3D -> {
                Transform3D transform = value.asTransform3D();
                Basis basis = transform.basis();
                yield NativeVariantSnapshot.reals(
                        value.type(),
                        concat(
                                vector3(basis.x()),
                                vector3(basis.y()),
                                vector3(basis.z()),
                                vector3(transform.origin())));
            }
            case PROJECTION -> {
                Projection projection = value.asProjection();
                yield NativeVariantSnapshot.reals(
                        value.type(),
                        concat(
                                vector4(projection.x()),
                                vector4(projection.y()),
                                vector4(projection.z()),
                                vector4(projection.w())));
            }
            case COLOR -> {
                Color color = value.asColor();
                yield NativeVariantSnapshot.reals(
                        value.type(),
                        new double[] {color.red(), color.green(), color.blue(), color.alpha()});
            }
            case STRING_NAME ->
                    NativeVariantSnapshot.text(value.type(), value.asStringName().value());
            case NODE_PATH -> NativeVariantSnapshot.text(value.type(), value.asNodePath().value());
            case RID -> {
                Rid rid = value.asRid();
                yield rid.isNativeBacked()
                        ? NativeVariantSnapshot.nativeIdentity(
                                value.type(),
                                rid.nativeContextHandle(),
                                rid.nativeBridgeHandle(),
                                -1)
                        : NativeVariantSnapshot.integers(value.type(), new long[] {rid.id()});
            }
            case OBJECT ->
                    NativeVariantSnapshot.integers(
                            value.type(), new long[] {value.asObject().objectHandle()});
            case CALLABLE -> {
                FoundryCallable callable = value.asCallable();
                yield callable.isNativeBacked()
                        ? NativeVariantSnapshot.nativeIdentity(
                                value.type(),
                                callable.nativeContextHandle(),
                                callable.nativeBridgeHandle(),
                                callable.arity())
                        : NativeVariantSnapshot.callback(
                                value.type(),
                                callable,
                                localCallableId(callable),
                                callable.arity());
            }
            case SIGNAL -> {
                FoundrySignal signal = value.asSignal();
                yield NativeVariantSnapshot.nativeIdentity(
                        value.type(),
                        signal.nativeContextHandle(),
                        signal.nativeBridgeHandle(),
                        -1);
            }
            case DICTIONARY -> {
                FoundryDictionary.VariantSnapshot snapshot =
                        ((FoundryDictionary<?, ?>) value.value()).variantSnapshot();
                yield NativeVariantSnapshot.collection(
                        value.type(), snapshot.keys(), snapshot.values());
            }
            case ARRAY ->
                    NativeVariantSnapshot.collection(
                            value.type(),
                            new Variant[0],
                            ((FoundryArray<?>) value.value()).variantSnapshot());
            case PACKED_BYTE_ARRAY ->
                    NativeVariantSnapshot.collection(
                            value.type(),
                            new Variant[0],
                            variants(((PackedByteArray) value.value()).toArray()));
            case PACKED_INT32_ARRAY ->
                    NativeVariantSnapshot.collection(
                            value.type(),
                            new Variant[0],
                            variants(((PackedInt32Array) value.value()).toArray()));
            case PACKED_INT64_ARRAY ->
                    NativeVariantSnapshot.collection(
                            value.type(),
                            new Variant[0],
                            variants(((PackedInt64Array) value.value()).toArray()));
            case PACKED_FLOAT32_ARRAY ->
                    NativeVariantSnapshot.collection(
                            value.type(),
                            new Variant[0],
                            variants(((PackedFloat32Array) value.value()).toArray()));
            case PACKED_FLOAT64_ARRAY ->
                    NativeVariantSnapshot.collection(
                            value.type(),
                            new Variant[0],
                            variants(((PackedFloat64Array) value.value()).toArray()));
            case PACKED_STRING_ARRAY ->
                    NativeVariantSnapshot.collection(
                            value.type(),
                            new Variant[0],
                            variants(((PackedStringArray) value.value()).toArray()));
            case PACKED_VECTOR2_ARRAY ->
                    NativeVariantSnapshot.collection(
                            value.type(),
                            new Variant[0],
                            variants(((PackedVector2Array) value.value()).toArray()));
            case PACKED_VECTOR3_ARRAY ->
                    NativeVariantSnapshot.collection(
                            value.type(),
                            new Variant[0],
                            variants(((PackedVector3Array) value.value()).toArray()));
            case PACKED_COLOR_ARRAY ->
                    NativeVariantSnapshot.collection(
                            value.type(),
                            new Variant[0],
                            variants(((PackedColorArray) value.value()).toArray()));
            case PACKED_VECTOR4_ARRAY ->
                    NativeVariantSnapshot.collection(
                            value.type(),
                            new Variant[0],
                            variants(((PackedVector4Array) value.value()).toArray()));
        };
    }

    private static Variant invokeLocalCallableV1(
            long contextHandle, FoundryCallable callable, Variant[] arguments) {
        Variant result =
                Objects.requireNonNull(callable, "callable")
                        .call(List.of(Objects.requireNonNull(arguments, "arguments")));
        requireEngine(contextHandle)
                .validateBridgeValue(
                        Objects.requireNonNull(result, "callable result"), "callback return");
        return result;
    }

    private static String[] nativeDispatchArgumentTypesV1(FoundryNativeDispatch dispatch) {
        return Objects.requireNonNull(dispatch, "dispatch")
                .argumentNativeTypes()
                .toArray(String[]::new);
    }

    private static FoundryMemberDescriptor[] nativeRegistrationMembersV1(
            FoundryClassDescriptor descriptor) {
        return Objects.requireNonNull(descriptor, "descriptor")
                .members()
                .toArray(FoundryMemberDescriptor[]::new);
    }

    private static FoundryExtensionAccess nativeRegistrationAccessV1(
            FoundryClassDescriptor descriptor) {
        return Objects.requireNonNull(descriptor, "descriptor").access();
    }

    private static FoundryMemberDetails nativeRegistrationDetailsV1(
            FoundryMemberDescriptor descriptor) {
        return Objects.requireNonNull(descriptor, "descriptor").details();
    }

    private static String nativeRegistrationFoundryTypeV1(String javaName) {
        if (javaName == null || javaName.isBlank()) {
            return null;
        }
        FoundryNativeEngine engine = ACTIVE_REGISTRATION_ENGINE.get();
        if (engine == null) {
            return null;
        }
        FoundryBindingContext context = engine.bindingContext.get();
        if (context == null || !context.isAlive()) {
            return null;
        }
        return context.foundryTypeForJavaName(javaName);
    }

    private static Object nativeConstructExtensionV1(
            long contextHandle, FoundryExtensionAccess access, long objectHandle) {
        FoundryBindingContext context = requireLiveBindingContext(contextHandle);
        FoundryExtensionAccess checkedAccess = Objects.requireNonNull(access, "access");
        return context.bind(
                objectHandle,
                ObjectOwnership.BORROWED,
                FoundryObject.class,
                (activeContext, lease) -> {
                    Object constructed =
                            Objects.requireNonNull(
                                    checkedAccess.construct(activeContext, lease),
                                    "constructed extension");
                    if (!(constructed instanceof FoundryObject foundryObject)) {
                        throw new IllegalArgumentException(
                                "Generated extension construction must return FoundryObject.");
                    }
                    return foundryObject;
                });
    }

    private static Variant nativeInvokeExtensionV1(
            long contextHandle,
            FoundryExtensionAccess access,
            Object target,
            String javaName,
            String[] argumentJavaTypes,
            String returnJavaType,
            Variant[] arguments) {
        requireLiveBindingContext(contextHandle);
        FoundryExtensionAccess checkedAccess = Objects.requireNonNull(access, "access");
        Object checkedTarget = Objects.requireNonNull(target, "target");
        String checkedName = requireText(javaName, "javaName");
        String[] types = Objects.requireNonNull(argumentJavaTypes, "argumentJavaTypes").clone();
        Variant[] values = Objects.requireNonNull(arguments, "arguments").clone();
        if (types.length != values.length) {
            throw new IllegalArgumentException(
                    "Extension callback "
                            + checkedName
                            + " expected "
                            + types.length
                            + " arguments but received "
                            + values.length
                            + ".");
        }
        Object[] converted = new Object[types.length];
        for (int index = 0; index < types.length; index++) {
            converted[index] =
                    registrationArgument(
                            requireText(types[index], "argumentJavaTypes[" + index + "]"),
                            Objects.requireNonNull(values[index], "arguments[" + index + "]"));
        }
        Object result = checkedAccess.invoke(checkedTarget, checkedName, converted);
        return registrationResult(requireText(returnJavaType, "returnJavaType"), result);
    }

    private static Variant nativeGetExtensionPropertyV1(
            long contextHandle,
            FoundryExtensionAccess access,
            Object target,
            String javaName,
            String javaType) {
        requireLiveBindingContext(contextHandle);
        Object result =
                Objects.requireNonNull(access, "access")
                        .getProperty(
                                Objects.requireNonNull(target, "target"),
                                requireText(javaName, "javaName"));
        return registrationResult(requireText(javaType, "javaType"), result);
    }

    private static void nativeSetExtensionPropertyV1(
            long contextHandle,
            FoundryExtensionAccess access,
            Object target,
            String javaName,
            String javaType,
            Variant value) {
        requireLiveBindingContext(contextHandle);
        Objects.requireNonNull(access, "access")
                .setProperty(
                        Objects.requireNonNull(target, "target"),
                        requireText(javaName, "javaName"),
                        registrationArgument(
                                requireText(javaType, "javaType"),
                                Objects.requireNonNull(value, "value")));
    }

    private static String nativeExtensionToStringV1(Object target) {
        return Objects.requireNonNull(
                Objects.requireNonNull(target, "target").toString(), "extension string");
    }

    private static Object registrationArgument(String javaType, Variant value) {
        return switch (javaType) {
            case "boolean" -> value.asBoolean();
            case "byte" -> (byte) registrationInteger(value, Byte.MIN_VALUE, Byte.MAX_VALUE, "byte");
            case "short" ->
                    (short) registrationInteger(value, Short.MIN_VALUE, Short.MAX_VALUE, "short");
            case "int" ->
                    (int)
                            registrationInteger(
                                    value, Integer.MIN_VALUE, Integer.MAX_VALUE, "int");
            case "long" -> value.asLong();
            case "char" ->
                    (char)
                            registrationInteger(
                                    value, Character.MIN_VALUE, Character.MAX_VALUE, "char");
            case "float" -> value.asFloat();
            case "double" -> value.asDouble();
            case "String", "java.lang.String" -> value.isNil() ? null : value.asString();
            case "void" ->
                    throw new IllegalArgumentException(
                            "void is not a valid extension callback argument type.");
            default -> value.isNil() ? null : value.value();
        };
    }

    private static long registrationInteger(Variant value, long minimum, long maximum, String type) {
        long numeric = value.asLong();
        if (numeric < minimum || numeric > maximum) {
            throw new IllegalArgumentException(
                    "Extension callback integer "
                            + numeric
                            + " is outside Java "
                            + type
                            + " range.");
        }
        return numeric;
    }

    private static Variant registrationResult(String javaType, Object value) {
        return switch (javaType) {
            case "void" -> Variant.nil();
            case "boolean" -> Variant.of(exactResult(value, Boolean.class, javaType));
            case "byte" -> Variant.of(exactResult(value, Byte.class, javaType));
            case "short" -> Variant.of(exactResult(value, Short.class, javaType));
            case "int" -> Variant.of(exactResult(value, Integer.class, javaType));
            case "long" -> Variant.of(exactResult(value, Long.class, javaType));
            case "char" ->
                    Variant.of(
                            (long) exactResult(value, Character.class, javaType).charValue());
            case "float" -> Variant.of(exactResult(value, Float.class, javaType));
            case "double" -> Variant.of(exactResult(value, Double.class, javaType));
            case "String", "java.lang.String" ->
                    value == null ? Variant.nil() : Variant.of(exactResult(value, String.class, javaType));
            default -> Variant.of(value);
        };
    }

    private static <T> T exactResult(Object value, Class<T> expected, String javaType) {
        if (!expected.isInstance(value)) {
            throw new IllegalArgumentException(
                    "Extension callback returned "
                            + (value == null ? "null" : value.getClass().getName())
                            + " for Java "
                            + javaType
                            + ".");
        }
        return expected.cast(value);
    }

    private static FoundryBindingContext requireLiveBindingContext(long contextHandle) {
        FoundryNativeEngine engine = requireEngine(contextHandle);
        FoundryBindingContext context = engine.bindingContext.get();
        if (context == null || !context.isAlive()) {
            throw new IllegalStateException("native_object_binding_context_unavailable");
        }
        return context;
    }

    private static long localCallableId(FoundryCallable callable) {
        synchronized (LOCAL_CALLABLE_IDS) {
            return LOCAL_CALLABLE_IDS.computeIfAbsent(
                    callable,
                    ignored -> {
                        long identity = NEXT_LOCAL_CALLABLE_ID.incrementAndGet();
                        if (identity == 0) {
                            throw new IllegalStateException(
                                    "Local Callable identity space exhausted.");
                        }
                        return identity;
                    });
        }
    }

    private static VariantType variantTypeFromWireCode(int wireCode) {
        VariantType[] types = VariantType.values();
        if (wireCode < 0 || wireCode >= types.length) {
            throw new IllegalArgumentException("invalid_native_variant_type:" + wireCode);
        }
        return types[wireCode];
    }

    private static Variant nativeVariantFromSnapshotV1(
            long contextHandle, long bridgeHandle, NativeVariantSnapshot snapshot) {
        NativeVariantSnapshot value = Objects.requireNonNull(snapshot, "snapshot");
        VariantType type = variantTypeFromWireCode(value.type());
        return switch (type) {
            case NIL -> Variant.nil();
            case BOOLEAN -> Variant.of(value.integers()[0] != 0);
            case INTEGER -> Variant.of(value.integers()[0]);
            case FLOAT -> Variant.of(value.reals()[0]);
            case STRING -> Variant.of(value.text());
            case VECTOR2 -> Variant.of(new Vector2(value.reals()[0], value.reals()[1]));
            case VECTOR2I ->
                    Variant.of(new Vector2i((int) value.integers()[0], (int) value.integers()[1]));
            case RECT2 ->
                    Variant.of(new Rect2(vector2(value.reals(), 0), vector2(value.reals(), 2)));
            case RECT2I ->
                    Variant.of(
                            new Rect2i(
                                    vector2i(value.integers(), 0), vector2i(value.integers(), 2)));
            case VECTOR3 -> Variant.of(vector3(value.reals(), 0));
            case VECTOR3I -> Variant.of(vector3i(value.integers(), 0));
            case TRANSFORM2D ->
                    Variant.of(
                            new Transform2D(
                                    vector2(value.reals(), 0),
                                    vector2(value.reals(), 2),
                                    vector2(value.reals(), 4)));
            case VECTOR4 -> Variant.of(vector4(value.reals(), 0));
            case VECTOR4I -> Variant.of(vector4i(value.integers(), 0));
            case PLANE -> Variant.of(new Plane(vector3(value.reals(), 0), value.reals()[3]));
            case QUATERNION ->
                    Variant.of(
                            new Quaternion(
                                    value.reals()[0],
                                    value.reals()[1],
                                    value.reals()[2],
                                    value.reals()[3]));
            case AABB -> Variant.of(new Aabb(vector3(value.reals(), 0), vector3(value.reals(), 3)));
            case BASIS ->
                    Variant.of(
                            new Basis(
                                    vector3(value.reals(), 0),
                                    vector3(value.reals(), 3),
                                    vector3(value.reals(), 6)));
            case TRANSFORM3D ->
                    Variant.of(
                            new Transform3D(
                                    new Basis(
                                            vector3(value.reals(), 0),
                                            vector3(value.reals(), 3),
                                            vector3(value.reals(), 6)),
                                    vector3(value.reals(), 9)));
            case PROJECTION ->
                    Variant.of(
                            new Projection(
                                    vector4(value.reals(), 0),
                                    vector4(value.reals(), 4),
                                    vector4(value.reals(), 8),
                                    vector4(value.reals(), 12)));
            case COLOR ->
                    Variant.of(
                            new Color(
                                    value.reals()[0],
                                    value.reals()[1],
                                    value.reals()[2],
                                    value.reals()[3]));
            case STRING_NAME -> Variant.of(new StringName(value.text()));
            case NODE_PATH -> Variant.of(new NodePath(value.text()));
            case RID -> Variant.of(nativeRidFromBridge(contextHandle, bridgeHandle));
            case OBJECT -> Variant.ofObject(nativeObjectFromBridge(contextHandle, bridgeHandle));
            case CALLABLE ->
                    Variant.ofCallable(nativeCallableFromBridge(contextHandle, bridgeHandle));
            case SIGNAL -> Variant.ofSignal(nativeSignalFromBridge(contextHandle, bridgeHandle));
            case DICTIONARY -> {
                Variant[] keys = value.keys();
                Variant[] values = value.values();
                if (keys.length != values.length) {
                    throw new IllegalArgumentException("invalid_native_dictionary_snapshot");
                }
                FoundryDictionary<Variant, Variant> dictionary =
                        new FoundryDictionary<>(VariantCodec.VARIANT, VariantCodec.VARIANT);
                for (int index = 0; index < keys.length; index++) {
                    dictionary.putVariants(keys[index], values[index]);
                }
                yield Variant.of(dictionary);
            }
            case ARRAY -> {
                FoundryArray<Variant> array = FoundryArray.untyped();
                for (Variant element : value.values()) {
                    array.addVariant(element);
                }
                yield Variant.of(array);
            }
            case PACKED_BYTE_ARRAY -> Variant.of(new PackedByteArray(byteArray(value.values())));
            case PACKED_INT32_ARRAY -> Variant.of(new PackedInt32Array(intArray(value.values())));
            case PACKED_INT64_ARRAY -> Variant.of(new PackedInt64Array(longArray(value.values())));
            case PACKED_FLOAT32_ARRAY ->
                    Variant.of(new PackedFloat32Array(floatArray(value.values())));
            case PACKED_FLOAT64_ARRAY ->
                    Variant.of(new PackedFloat64Array(doubleArray(value.values())));
            case PACKED_STRING_ARRAY ->
                    Variant.of(new PackedStringArray(stringArray(value.values())));
            case PACKED_VECTOR2_ARRAY ->
                    Variant.of(new PackedVector2Array(vector2Array(value.values())));
            case PACKED_VECTOR3_ARRAY ->
                    Variant.of(new PackedVector3Array(vector3Array(value.values())));
            case PACKED_COLOR_ARRAY -> Variant.of(new PackedColorArray(colorArray(value.values())));
            case PACKED_VECTOR4_ARRAY ->
                    Variant.of(new PackedVector4Array(vector4Array(value.values())));
        };
    }

    private static double[] vector2(Vector2 value) {
        return new double[] {value.x(), value.y()};
    }

    private static long[] vector2i(Vector2i value) {
        return new long[] {value.x(), value.y()};
    }

    private static double[] vector3(Vector3 value) {
        return new double[] {value.x(), value.y(), value.z()};
    }

    private static long[] vector3i(Vector3i value) {
        return new long[] {value.x(), value.y(), value.z()};
    }

    private static double[] vector4(Vector4 value) {
        return new double[] {value.x(), value.y(), value.z(), value.w()};
    }

    private static long[] vector4i(Vector4i value) {
        return new long[] {value.x(), value.y(), value.z(), value.w()};
    }

    private static double[] quaternion(Quaternion value) {
        return new double[] {value.x(), value.y(), value.z(), value.w()};
    }

    private static Vector2 vector2(double[] values, int offset) {
        return new Vector2(values[offset], values[offset + 1]);
    }

    private static Vector2i vector2i(long[] values, int offset) {
        return new Vector2i((int) values[offset], (int) values[offset + 1]);
    }

    private static Vector3 vector3(double[] values, int offset) {
        return new Vector3(values[offset], values[offset + 1], values[offset + 2]);
    }

    private static Vector3i vector3i(long[] values, int offset) {
        return new Vector3i(
                (int) values[offset], (int) values[offset + 1], (int) values[offset + 2]);
    }

    private static Vector4 vector4(double[] values, int offset) {
        return new Vector4(
                values[offset], values[offset + 1], values[offset + 2], values[offset + 3]);
    }

    private static Vector4i vector4i(long[] values, int offset) {
        return new Vector4i(
                (int) values[offset],
                (int) values[offset + 1],
                (int) values[offset + 2],
                (int) values[offset + 3]);
    }

    private static double[] concat(double[]... arrays) {
        int length = Arrays.stream(arrays).mapToInt(array -> array.length).sum();
        double[] result = new double[length];
        int offset = 0;
        for (double[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

    private static long[] concat(long[]... arrays) {
        int length = Arrays.stream(arrays).mapToInt(array -> array.length).sum();
        long[] result = new long[length];
        int offset = 0;
        for (long[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

    private static Variant[] variants(byte[] values) {
        Variant[] result = new Variant[values.length];
        for (int index = 0; index < values.length; index++)
            result[index] = Variant.of(values[index]);
        return result;
    }

    private static Variant[] variants(int[] values) {
        return Arrays.stream(values).mapToObj(Variant::of).toArray(Variant[]::new);
    }

    private static Variant[] variants(long[] values) {
        return Arrays.stream(values).mapToObj(Variant::of).toArray(Variant[]::new);
    }

    private static Variant[] variants(float[] values) {
        Variant[] result = new Variant[values.length];
        for (int index = 0; index < values.length; index++)
            result[index] = Variant.of(values[index]);
        return result;
    }

    private static Variant[] variants(double[] values) {
        return Arrays.stream(values).mapToObj(Variant::of).toArray(Variant[]::new);
    }

    private static Variant[] variants(Object[] values) {
        return Arrays.stream(values).map(Variant::of).toArray(Variant[]::new);
    }

    private static byte[] byteArray(Variant[] values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++)
            result[index] = (byte) values[index].asLong();
        return result;
    }

    private static int[] intArray(Variant[] values) {
        return Arrays.stream(values).mapToInt(Variant::asInt).toArray();
    }

    private static long[] longArray(Variant[] values) {
        return Arrays.stream(values).mapToLong(Variant::asLong).toArray();
    }

    private static float[] floatArray(Variant[] values) {
        float[] result = new float[values.length];
        for (int index = 0; index < values.length; index++) result[index] = values[index].asFloat();
        return result;
    }

    private static double[] doubleArray(Variant[] values) {
        return Arrays.stream(values).mapToDouble(Variant::asDouble).toArray();
    }

    private static String[] stringArray(Variant[] values) {
        return Arrays.stream(values).map(Variant::asString).toArray(String[]::new);
    }

    private static Vector2[] vector2Array(Variant[] values) {
        return Arrays.stream(values).map(Variant::asVector2).toArray(Vector2[]::new);
    }

    private static Vector3[] vector3Array(Variant[] values) {
        return Arrays.stream(values).map(Variant::asVector3).toArray(Vector3[]::new);
    }

    private static Color[] colorArray(Variant[] values) {
        return Arrays.stream(values).map(Variant::asColor).toArray(Color[]::new);
    }

    private static Vector4[] vector4Array(Variant[] values) {
        return Arrays.stream(values).map(Variant::asVector4).toArray(Vector4[]::new);
    }

    private record NativeVariantSnapshot(
            int type,
            long[] integers,
            double[] reals,
            String text,
            Variant[] keys,
            Variant[] values,
            long nativeContext,
            long nativeHandle,
            FoundryCallable callback,
            int callableArity) {
        private NativeVariantSnapshot {
            integers = integers.clone();
            reals = reals.clone();
            keys = keys.clone();
            values = values.clone();
        }

        static NativeVariantSnapshot empty(VariantType type) {
            return new NativeVariantSnapshot(
                    type.ordinal(),
                    new long[0],
                    new double[0],
                    "",
                    new Variant[0],
                    new Variant[0],
                    0,
                    0,
                    null,
                    -1);
        }

        static NativeVariantSnapshot integers(VariantType type, long[] values) {
            NativeVariantSnapshot empty = empty(type);
            return new NativeVariantSnapshot(
                    empty.type,
                    values,
                    empty.reals,
                    empty.text,
                    empty.keys,
                    empty.values,
                    0,
                    0,
                    null,
                    -1);
        }

        static NativeVariantSnapshot reals(VariantType type, double[] values) {
            NativeVariantSnapshot empty = empty(type);
            return new NativeVariantSnapshot(
                    empty.type,
                    empty.integers,
                    values,
                    empty.text,
                    empty.keys,
                    empty.values,
                    0,
                    0,
                    null,
                    -1);
        }

        static NativeVariantSnapshot text(VariantType type, String value) {
            NativeVariantSnapshot empty = empty(type);
            return new NativeVariantSnapshot(
                    empty.type,
                    empty.integers,
                    empty.reals,
                    value,
                    empty.keys,
                    empty.values,
                    0,
                    0,
                    null,
                    -1);
        }

        static NativeVariantSnapshot collection(
                VariantType type, Variant[] keys, Variant[] values) {
            NativeVariantSnapshot empty = empty(type);
            return new NativeVariantSnapshot(
                    empty.type,
                    empty.integers,
                    empty.reals,
                    empty.text,
                    keys,
                    values,
                    0,
                    0,
                    null,
                    -1);
        }

        static NativeVariantSnapshot nativeIdentity(
                VariantType type, long context, long handle, int arity) {
            NativeVariantSnapshot empty = empty(type);
            return new NativeVariantSnapshot(
                    empty.type,
                    empty.integers,
                    empty.reals,
                    empty.text,
                    empty.keys,
                    empty.values,
                    context,
                    handle,
                    null,
                    arity);
        }

        static NativeVariantSnapshot callback(
                VariantType type, FoundryCallable callback, long identity, int arity) {
            NativeVariantSnapshot empty = empty(type);
            return new NativeVariantSnapshot(
                    empty.type,
                    new long[] {identity},
                    empty.reals,
                    empty.text,
                    empty.keys,
                    empty.values,
                    0,
                    0,
                    callback,
                    arity);
        }
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

        void reportCallbackException(long contextHandle, long callbackHandle, Throwable failure);

        void registerExtensionClass(long contextHandle, FoundryClassDescriptor descriptor);

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
        public void registerExtensionClass(long contextHandle, FoundryClassDescriptor descriptor) {
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
