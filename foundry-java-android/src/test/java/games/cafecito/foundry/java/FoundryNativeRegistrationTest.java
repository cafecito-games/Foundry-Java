package games.cafecito.foundry.java;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.cafecito.foundry.generated.GeneratedNativeDispatch;
import games.cafecito.foundry.generated.GeneratedRegistration;
import games.cafecito.foundry.generated.classes.Node3D;
import games.cafecito.foundry.runtime.FoundryBindingContext;
import games.cafecito.foundry.runtime.FoundryClassDescriptor;
import games.cafecito.foundry.runtime.FoundryEngine;
import games.cafecito.foundry.runtime.FoundryExtensionAccess;
import games.cafecito.foundry.runtime.FoundryMemberDescriptor;
import games.cafecito.foundry.runtime.FoundryMemberDetails;
import games.cafecito.foundry.runtime.FoundryModuleDescriptor;
import games.cafecito.foundry.runtime.FoundryModuleProvider;
import games.cafecito.foundry.runtime.FoundryNativeDispatch;
import games.cafecito.foundry.runtime.FoundryObject;
import games.cafecito.foundry.runtime.FoundryRegistryBootstrap;
import games.cafecito.foundry.runtime.FoundryRegistryCoordinator;
import games.cafecito.foundry.runtime.FoundryRuntime;
import games.cafecito.foundry.runtime.ObjectLease;
import games.cafecito.foundry.types.Variant;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class FoundryNativeRegistrationTest {
    @Test
    void exposesExactTypedRegistrationArraysAccessAndDetails() throws Exception {
        FoundryExtensionAccess access = noOpAccess();
        FoundryMemberDescriptor method =
                new FoundryMemberDescriptor("method", "reset", "reset", "void()");
        FoundryMemberDescriptor legacyProperty =
                new FoundryMemberDescriptor("property", "speed", "speed", "double");
        FoundryClassDescriptor descriptor =
                new FoundryClassDescriptor(
                        "demo.SpinningCube",
                        "SpinningCube",
                        "Node3D",
                        "SCENE",
                        List.of(),
                        access,
                        List.of(method, legacyProperty));

        Method members =
                privateStatic(
                        "nativeRegistrationMembersV1",
                        FoundryMemberDescriptor[].class,
                        FoundryClassDescriptor.class);
        Method classAccess =
                privateStatic(
                        "nativeRegistrationAccessV1",
                        FoundryExtensionAccess.class,
                        FoundryClassDescriptor.class);
        Method details =
                privateStatic(
                        "nativeRegistrationDetailsV1",
                        FoundryMemberDetails.class,
                        FoundryMemberDescriptor.class);

        assertArrayEquals(
                new FoundryMemberDescriptor[] {method, legacyProperty},
                (FoundryMemberDescriptor[]) members.invoke(null, descriptor));
        assertSame(access, classAccess.invoke(null, descriptor));
        assertSame(method.details(), details.invoke(null, method));
        assertSame(legacyProperty.details(), details.invoke(null, legacyProperty));
    }

    @Test
    void constructsExtensionInstancesThroughTheLiveBorrowedContext() throws Exception {
        Method construct =
                privateStatic(
                        "nativeConstructExtensionV1",
                        Object.class,
                        long.class,
                        FoundryExtensionAccess.class,
                        long.class);
        RegistrationGateway gateway = new RegistrationGateway();
        gateway.objectType = "SpinningCube";
        FoundryNativeEngine engine =
                new FoundryNativeEngine(12, GeneratedNativeDispatch::require, gateway);
        FoundryBindingContext context = new FoundryBindingContext(12, engine);
        RecordingAccess access = new RecordingAccess();
        access.constructed = true;

        Object first = construct.invoke(null, 12L, access, 71L);
        Object second = construct.invoke(null, 12L, access, 71L);

        assertInstanceOf(TestExtension.class, first);
        assertSame(first, second);
        assertEquals(1, access.constructions);
        assertSame(context, ((TestExtension) first).context());
        context.close();
    }

    @Test
    void boxesExactPrimitiveWidthsEnumsAndGeneratedObjectsForDirectInvocation() throws Exception {
        Method invoke =
                privateStatic(
                        "nativeInvokeExtensionV1",
                        Variant.class,
                        long.class,
                        FoundryExtensionAccess.class,
                        Object.class,
                        String.class,
                        String[].class,
                        String.class,
                        Variant[].class);
        RegistrationGateway gateway = new RegistrationGateway();
        gateway.objectType = "Node3D";
        FoundryNativeEngine engine =
                new FoundryNativeEngine(13, GeneratedNativeDispatch::require, gateway);
        FoundryBindingContext context = new FoundryBindingContext(13, engine);
        Node3D node = (Node3D) FoundryNativeEngine.nativeObjectFromBridge(13, 72);
        RecordingAccess access = new RecordingAccess();
        access.result = Character.valueOf(Character.MAX_VALUE);

        Variant result =
                (Variant)
                        invoke.invoke(
                                null,
                                13L,
                                access,
                                new Object(),
                                "convert",
                                new String[] {
                                    "boolean",
                                    "byte",
                                    "short",
                                    "int",
                                    "long",
                                    "char",
                                    "float",
                                    "double",
                                    "demo.UserMode",
                                    Node3D.class.getName()
                                },
                                "char",
                                new Variant[] {
                                    Variant.of(true),
                                    Variant.of(Byte.MIN_VALUE),
                                    Variant.of(Short.MAX_VALUE),
                                    Variant.of(Integer.MIN_VALUE),
                                    Variant.of(Long.MAX_VALUE),
                                    Variant.of((long) Character.MAX_VALUE),
                                    Variant.of(1.5f),
                                    Variant.of(2.25d),
                                    Variant.of(Long.MIN_VALUE),
                                    Variant.ofObject(node)
                                });

        assertEquals(Character.MAX_VALUE, result.asLong());
        assertEquals(
                List.of(
                        Boolean.class,
                        Byte.class,
                        Short.class,
                        Integer.class,
                        Long.class,
                        Character.class,
                        Float.class,
                        Double.class,
                        Long.class,
                        Node3D.class),
                Arrays.stream(access.arguments).map(Object::getClass).toList());
        assertSame(node, access.arguments[9]);
        context.close();
    }

    @Test
    void rejectsNarrowingAndArityFailuresBeforeInvokingExtensionCode() throws Exception {
        Method invoke =
                privateStatic(
                        "nativeInvokeExtensionV1",
                        Variant.class,
                        long.class,
                        FoundryExtensionAccess.class,
                        Object.class,
                        String.class,
                        String[].class,
                        String.class,
                        Variant[].class);
        RegistrationGateway gateway = new RegistrationGateway();
        FoundryNativeEngine engine =
                new FoundryNativeEngine(14, GeneratedNativeDispatch::require, gateway);
        FoundryBindingContext context = new FoundryBindingContext(14, engine);
        RecordingAccess access = new RecordingAccess();

        InvocationTargetException byteOverflow =
                assertThrows(
                        InvocationTargetException.class,
                        () ->
                                invoke.invoke(
                                        null,
                                        14L,
                                        access,
                                        new Object(),
                                        "convert",
                                        new String[] {"byte"},
                                        "void",
                                        new Variant[] {Variant.of(128L)}));
        InvocationTargetException charUnderflow =
                assertThrows(
                        InvocationTargetException.class,
                        () ->
                                invoke.invoke(
                                        null,
                                        14L,
                                        access,
                                        new Object(),
                                        "convert",
                                        new String[] {"char"},
                                        "void",
                                        new Variant[] {Variant.of(-1L)}));
        InvocationTargetException arity =
                assertThrows(
                        InvocationTargetException.class,
                        () ->
                                invoke.invoke(
                                        null,
                                        14L,
                                        access,
                                        new Object(),
                                        "convert",
                                        new String[] {"int"},
                                        "void",
                                        new Variant[0]));

        assertInstanceOf(IllegalArgumentException.class, byteOverflow.getCause());
        assertInstanceOf(IllegalArgumentException.class, charUnderflow.getCause());
        assertInstanceOf(IllegalArgumentException.class, arity.getCause());
        assertEquals(0, access.invocations);
        context.close();
    }

    @Test
    void adaptsPropertiesVoidDefaultsAndCallbackExceptionsWithoutReflection() throws Exception {
        Method get =
                privateStatic(
                        "nativeGetExtensionPropertyV1",
                        Variant.class,
                        long.class,
                        FoundryExtensionAccess.class,
                        Object.class,
                        String.class,
                        String.class);
        Method set =
                privateStatic(
                        "nativeSetExtensionPropertyV1",
                        void.class,
                        long.class,
                        FoundryExtensionAccess.class,
                        Object.class,
                        String.class,
                        String.class,
                        Variant.class);
        Method invoke =
                privateStatic(
                        "nativeInvokeExtensionV1",
                        Variant.class,
                        long.class,
                        FoundryExtensionAccess.class,
                        Object.class,
                        String.class,
                        String[].class,
                        String.class,
                        Variant[].class);
        Method toString =
                privateStatic("nativeExtensionToStringV1", String.class, Object.class);
        RegistrationGateway gateway = new RegistrationGateway();
        FoundryNativeEngine engine =
                new FoundryNativeEngine(15, GeneratedNativeDispatch::require, gateway);
        FoundryBindingContext context = new FoundryBindingContext(15, engine);
        RecordingAccess access = new RecordingAccess();

        access.result = Long.valueOf(91);
        assertEquals(91, ((Variant) get.invoke(null, 15L, access, access, "mode", "long")).asLong());
        set.invoke(null, 15L, access, access, "letter", "char", Variant.of(65L));
        assertEquals(Character.valueOf('A'), access.propertyValue);

        access.result = null;
        assertTrue(
                ((Variant)
                                invoke.invoke(
                                        null,
                                        15L,
                                        access,
                                        access,
                                        "reset",
                                        new String[0],
                                        "void",
                                        new Variant[0]))
                        .isNil());
        assertEquals(access.toString(), toString.invoke(null, access));

        RuntimeException callbackFailure = new RuntimeException("callback_failed");
        access.failure = callbackFailure;
        InvocationTargetException propagated =
                assertThrows(
                        InvocationTargetException.class,
                        () ->
                                invoke.invoke(
                                        null,
                                        15L,
                                        access,
                                        access,
                                        "fail",
                                        new String[0],
                                        "void",
                                        new Variant[0]));
        assertSame(callbackFailure, propagated.getCause());
        context.close();
    }

    @Test
    void installsGeneratedRegistrationBeforePublishingTheWeakContext() throws Exception {
        long contextHandle = 16;
        RegistrationGateway gateway = new RegistrationGateway();
        gateway.objectType = "Node3D";
        Consumer<FoundryBindingContext> registrar =
                context -> {
                    IllegalStateException unpublished =
                            assertThrows(
                                    IllegalStateException.class,
                                    () ->
                                            FoundryNativeEngine.nativeObjectFromBridge(
                                                    contextHandle, 71));
                    assertEquals(
                            "native_object_binding_context_unavailable", unpublished.getMessage());
                    GeneratedRegistration.registerAll(context);
                };
        Constructor<FoundryNativeEngine> constructor =
                FoundryNativeEngine.class.getDeclaredConstructor(
                        long.class, Function.class, FoundryNativeEngine.NativeGateway.class, Consumer.class);
        constructor.setAccessible(true);
        Function<String, FoundryNativeDispatch> dispatchLookup = GeneratedNativeDispatch::require;
        FoundryNativeEngine engine =
                constructor.newInstance(contextHandle, dispatchLookup, gateway, registrar);

        FoundryBindingContext context = new FoundryBindingContext(contextHandle, engine);
        Object first = FoundryNativeEngine.nativeObjectFromBridge(contextHandle, 71);
        Object second = FoundryNativeEngine.nativeObjectFromBridge(contextHandle, 71);

        assertInstanceOf(Node3D.class, first);
        assertSame(first, second);
        context.close();
    }

    @Test
    void resolvesGeneratedAndWholeCatalogObjectTypesOnlyDuringRegistration()
            throws Exception {
        Method resolve =
                privateStatic(
                        "nativeRegistrationFoundryTypeV1", String.class, String.class);
        RegistrationGateway gateway = new RegistrationGateway();
        FoundryNativeEngine engine =
                new FoundryNativeEngine(17, GeneratedNativeDispatch::require, gateway);
        FoundryClassDescriptor first =
                descriptor("demo.RenamedExtension", "ExportedExtension");
        FoundryClassDescriptor second = descriptor("demo.Consumer", "Consumer");
        List<List<String>> resolutionHistory = new ArrayList<>();
        gateway.registrationProbe =
                descriptor -> {
                    if (descriptor.javaName().equals(first.javaName())) {
                        resolutionHistory.add(
                                List.of(
                                        invokeString(resolve, Node3D.class.getName()),
                                        invokeString(resolve, first.javaName())));
                    } else {
                        resolutionHistory.add(
                                List.of(invokeString(resolve, first.javaName())));
                    }
                };
        FoundryRegistryCoordinator coordinator =
                new FoundryRegistryCoordinator(
                        bootstrap(first, second), ignored -> engine);

        assertTrue(coordinator.initialize(17, 0));

        assertEquals(
                List.of(
                        List.of("ExportedExtension"),
                        List.of("Node3D", "ExportedExtension")),
                resolutionHistory);
        assertEquals(null, resolve.invoke(null, Node3D.class.getName()));
        coordinator.invalidate(17);
    }

    @Test
    void failedRegistrationDoesNotPublishItsJavaObjectType() throws Exception {
        Method resolve =
                privateStatic(
                        "nativeRegistrationFoundryTypeV1", String.class, String.class);
        RegistrationGateway gateway = new RegistrationGateway();
        FoundryNativeEngine engine =
                new FoundryNativeEngine(18, GeneratedNativeDispatch::require, gateway);
        FoundryBindingContext context = new FoundryBindingContext(18, engine);
        FoundryClassDescriptor failed = descriptor("demo.Failed", "FailedExport");
        gateway.registrationFailure = new IllegalArgumentException("object_type");

        assertThrows(
                IllegalArgumentException.class,
                () -> engine.registerExtensionClass(18, failed));

        gateway.registrationFailure = null;
        gateway.registrationProbe =
                ignored ->
                        gateway.resolvedTypes =
                                List.of(String.valueOf(invokeString(resolve, failed.javaName())));
        engine.registerExtensionClass(18, descriptor("demo.Next", "Next"));

        assertEquals(List.of("null"), gateway.resolvedTypes);
        context.close();
    }

    @Test
    void resolvesForwardAndCyclicRenamedTypesFromTheWholeValidatedCatalog()
            throws Exception {
        Method resolve =
                privateStatic(
                        "nativeRegistrationFoundryTypeV1", String.class, String.class);
        FoundryClassDescriptor alpha =
                descriptorWithMemberType(
                        "demo.AlphaExtension", "RenamedAlpha", "demo.ZetaExtension");
        FoundryClassDescriptor zeta =
                descriptorWithMemberType(
                        "demo.ZetaExtension", "RenamedZeta", "demo.AlphaExtension");
        RegistrationGateway gateway = new RegistrationGateway();
        FoundryNativeEngine engine =
                new FoundryNativeEngine(19, GeneratedNativeDispatch::require, gateway);
        gateway.registrationProbe =
                descriptor -> {
                    if (descriptor.javaName().equals(alpha.javaName())) {
                        gateway.resolvedTypes =
                                List.of(
                                        invokeString(resolve, zeta.javaName()),
                                        invokeString(resolve, alpha.javaName()));
                    } else {
                        gateway.resolvedTypes =
                                List.of(invokeString(resolve, alpha.javaName()));
                    }
                };
        FoundryRegistryCoordinator coordinator =
                new FoundryRegistryCoordinator(
                        bootstrap(alpha, zeta), ignored -> engine);

        assertTrue(coordinator.initialize(19, 0));

        assertEquals(List.of("RenamedAlpha"), gateway.resolvedTypes);
        coordinator.invalidate(19);
    }

    private static FoundryRegistryBootstrap bootstrap(
            FoundryClassDescriptor... descriptors) {
        FoundryModuleDescriptor module =
                new FoundryModuleDescriptor(
                        FoundryModuleDescriptor.CURRENT_FORMAT,
                        "demo",
                        "generated.demo",
                        FoundryRuntime.API_SHA256,
                        FoundryRuntime.GENERATOR_VERSION,
                        FoundryRuntime.RUNTIME_CONTRACT_VERSION,
                        FoundryRuntime.BRIDGE_CONTRACT_VERSION,
                        List.of(descriptors));
        FoundryModuleProvider provider = () -> module;
        return new FoundryRegistryBootstrap(List.of(provider));
    }

    private static FoundryClassDescriptor descriptorWithMemberType(
            String javaName, String foundryName, String peerJavaName) {
        FoundryClassDescriptor descriptor = descriptor(javaName, foundryName);
        return new FoundryClassDescriptor(
                descriptor.javaName(),
                descriptor.foundryName(),
                descriptor.baseName(),
                "CORE",
                descriptor.after(),
                descriptor.access(),
                List.of(
                        new FoundryMemberDescriptor(
                                "method", "peer", "peer", peerJavaName + "()")));
    }

    private static FoundryClassDescriptor descriptor(String javaName, String foundryName) {
        return new FoundryClassDescriptor(
                javaName,
                foundryName,
                "Node3D",
                "CORE",
                List.of(),
                noOpAccess(),
                List.of());
    }

    private static String invokeString(Method method, String argument) {
        try {
            return (String) method.invoke(null, argument);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Method privateStatic(String name, Class<?> returnType, Class<?>... parameters)
            throws Exception {
        Method method = FoundryNativeEngine.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        assertEquals(returnType, method.getReturnType());
        return method;
    }

    private static FoundryExtensionAccess noOpAccess() {
        return new FoundryExtensionAccess() {
            @Override
            public Object construct(FoundryBindingContext context, games.cafecito.foundry.runtime.ObjectLease lease) {
                return new Object();
            }

            @Override
            public Object invoke(Object target, String name, Object[] arguments) {
                return null;
            }

            @Override
            public Object getProperty(Object target, String name) {
                return null;
            }

            @Override
            public void setProperty(Object target, String name, Object value) {}
        };
    }

    private static final class RecordingAccess implements FoundryExtensionAccess {
        private boolean constructed;
        private int constructions;
        private int invocations;
        private Object[] arguments = new Object[0];
        private Object propertyValue;
        private Object result;
        private RuntimeException failure;

        @Override
        public Object construct(FoundryBindingContext context, ObjectLease lease) {
            constructions++;
            if (!constructed) {
                throw new IllegalStateException("construction_not_enabled");
            }
            return new TestExtension(context, lease);
        }

        @Override
        public Object invoke(Object target, String name, Object[] arguments) {
            invocations++;
            if (failure != null) {
                throw failure;
            }
            this.arguments = arguments;
            return result;
        }

        @Override
        public Object getProperty(Object target, String name) {
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @Override
        public void setProperty(Object target, String name, Object value) {
            if (failure != null) {
                throw failure;
            }
            propertyValue = value;
        }
    }

    private static final class TestExtension extends FoundryObject {
        private TestExtension(FoundryBindingContext context, ObjectLease lease) {
            super(context, lease);
        }
    }

    private static final class RegistrationGateway implements FoundryNativeEngine.NativeGateway {
        private String objectType = "Object";
        private Consumer<FoundryClassDescriptor> registrationProbe = ignored -> {};
        private RuntimeException registrationFailure;
        private List<String> resolvedTypes = List.of();

        @Override
        public FoundryEngine.CallResult call(
                long contextHandle,
                long objectHandle,
                FoundryNativeDispatch dispatch,
                Variant[] arguments) {
            return FoundryEngine.CallResult.success(Variant.nil());
        }

        @Override
        public Variant decodeVariant(long contextHandle, long variantHandle) {
            return Variant.nil();
        }

        @Override
        public long encodeVariant(long contextHandle, Variant value) {
            return 1;
        }

        @Override
        public boolean isObjectValid(long contextHandle, long objectHandle) {
            return true;
        }

        @Override
        public String objectType(long contextHandle, long objectHandle) {
            return objectType;
        }

        @Override
        public long instantiate(long contextHandle, String className) {
            return 1;
        }

        @Override
        public void retain(long contextHandle, long objectHandle) {}

        @Override
        public void release(long contextHandle, long objectHandle) {}

        @Override
        public long singleton(long contextHandle, String name) {
            return 1;
        }

        @Override
        public void reportCallbackException(
                long contextHandle, long callbackHandle, Throwable failure) {}

        @Override
        public void registerExtensionClass(
                long contextHandle, FoundryClassDescriptor descriptor) {
            registrationProbe.accept(descriptor);
            if (registrationFailure != null) {
                throw registrationFailure;
            }
        }

        @Override
        public void unregisterExtensionClass(long contextHandle, String foundryName) {}
    }
}
