package games.cafecito.foundry.samples.java.conformance;

import static games.cafecito.foundry.samples.java.ConformanceCategory.BUILT_IN_TYPES;
import static games.cafecito.foundry.samples.java.ConformanceCategory.CALLABLES;
import static games.cafecito.foundry.samples.java.ConformanceCategory.CALLS;
import static games.cafecito.foundry.samples.java.ConformanceCategory.DEFAULT_ARGUMENTS;
import static games.cafecito.foundry.samples.java.ConformanceCategory.ENGINE_CALL_ERRORS;
import static games.cafecito.foundry.samples.java.ConformanceCategory.EXCEPTIONS;
import static games.cafecito.foundry.samples.java.ConformanceCategory.OBJECT_IDENTITY;
import static games.cafecito.foundry.samples.java.ConformanceCategory.OPERATORS;
import static games.cafecito.foundry.samples.java.ConformanceCategory.OWNERSHIP;
import static games.cafecito.foundry.samples.java.ConformanceCategory.PACKED_ARRAYS;
import static games.cafecito.foundry.samples.java.ConformanceCategory.PROPERTIES;
import static games.cafecito.foundry.samples.java.ConformanceCategory.SIGNALS;
import static games.cafecito.foundry.samples.java.ConformanceCategory.SINGLETONS;
import static games.cafecito.foundry.samples.java.ConformanceCategory.TYPED_COLLECTIONS;
import static games.cafecito.foundry.samples.java.ConformanceCategory.UNTYPED_COLLECTIONS;
import static games.cafecito.foundry.samples.java.ConformanceCategory.UTILITY_FUNCTIONS;
import static games.cafecito.foundry.samples.java.ConformanceCategory.VIRTUAL_OVERRIDES;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import games.cafecito.foundry.generated.Utilities;
import games.cafecito.foundry.generated.builtins.Vector2Api;
import games.cafecito.foundry.generated.classes.Node;
import games.cafecito.foundry.runtime.FoundryBindingContext;
import games.cafecito.foundry.runtime.FoundryCallError;
import games.cafecito.foundry.runtime.FoundryCallException;
import games.cafecito.foundry.runtime.FoundryCallable;
import games.cafecito.foundry.runtime.FoundryClassDescriptor;
import games.cafecito.foundry.runtime.FoundryObject;
import games.cafecito.foundry.runtime.FoundrySignal;
import games.cafecito.foundry.runtime.FoundryTypedSignal;
import games.cafecito.foundry.runtime.ObjectOwnership;
import games.cafecito.foundry.samples.java.ConformanceSpinner;
import games.cafecito.foundry.samples.java.Covers;
import games.cafecito.foundry.samples.java.ScriptedEngine;
import games.cafecito.foundry.types.FoundryArray;
import games.cafecito.foundry.types.FoundryDictionary;
import games.cafecito.foundry.types.NodePath;
import games.cafecito.foundry.types.PackedInt32Array;
import games.cafecito.foundry.types.StringName;
import games.cafecito.foundry.types.Variant;
import games.cafecito.foundry.types.VariantCodec;
import games.cafecito.foundry.types.VariantConversionException;
import games.cafecito.foundry.types.VariantType;
import games.cafecito.foundry.types.Vector2;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Behavioural conformance for the approved Java API surface. */
public class ApiSurfaceConformanceTest {
    private static final String GET_NAME = "classes/Node/methods/get_name";
    private static final String SET_NAME = "classes/Node/methods/set_name";
    private static final String ADD_CHILD = "classes/Node/methods/add_child";
    private static final String GET_GROUPS = "classes/Node/methods/get_groups";
    private static final String GET_NODE_AND_RESOURCE =
            "classes/Node/methods/get_node_and_resource";
    private static final String CONNECT = "classes/Object/methods/connect";
    private static final String CHILD_ENTERED_TREE = "classes/Node/signals/child_entered_tree";

    private ScriptedEngine engine;
    private FoundryBindingContext context;

    @Before
    public void openContext() {
        engine = new ScriptedEngine();
        context = new FoundryBindingContext(ConformanceFixture.CONTEXT_HANDLE, engine);
    }

    @After
    public void closeContext() {
        context.close();
    }

    @Test
    @Covers(CALLS)
    public void aGeneratedCallTransportsItsMethodIdentityObjectHandleAndArguments() {
        long handle = engine.declareObject("Node");
        engine.respondWith(GET_NAME, Variant.of(new StringName("spinner")));
        Node node = Node.bind(context, handle);

        assertEquals(new StringName("spinner"), node.getName());

        ScriptedEngine.CallRecord observed = engine.onlyCallTo(GET_NAME);
        assertTrue(observed.methodIdentity().startsWith(GET_NAME + "#"));
        assertEquals(handle, observed.objectHandle());
        assertEquals(List.of(), observed.arguments());
    }

    @Test
    @Covers(DEFAULT_ARGUMENTS)
    public void anOmittedDefaultArgumentIsNotSynthesizedByTheBindingLayer() {
        long parentHandle = engine.declareObject("Node");
        long childHandle = engine.declareObject("Node");
        engine.respondWith(ADD_CHILD, Variant.nil());
        Node parent = Node.bind(context, parentHandle);
        Node child = Node.bind(context, childHandle);

        parent.addChild(child);
        parent.addChild(child, true);
        parent.addChild(child, true, Node.InternalMode.INTERNAL_MODE_FRONT);

        List<ScriptedEngine.CallRecord> observed = engine.callsTo(ADD_CHILD);
        assertEquals(3, observed.size());
        assertEquals(1, observed.get(0).arguments().size());
        assertEquals(2, observed.get(1).arguments().size());
        assertEquals(3, observed.get(2).arguments().size());
        assertSame(child, observed.get(0).arguments().get(0).asObject());
        assertTrue(observed.get(1).arguments().get(1).asBoolean());
        assertEquals(
                Node.InternalMode.INTERNAL_MODE_FRONT.value(),
                observed.get(2).arguments().get(2).asLong());
    }

    @Test
    @Covers(PROPERTIES)
    public void aClassPropertyIsReachedThroughItsGeneratedEngineMethods() {
        // Almost every generated class property is realized by the engine methods that back it,
        // because the accessor names collide with generated engine methods. The consumer path a
        // sample must therefore demonstrate is the engine-method path, not a synthetic accessor.
        long handle = engine.declareObject("Node");
        engine.respondWith(SET_NAME, Variant.nil());
        engine.respondWith(GET_NAME, Variant.of(new StringName("renamed")));
        Node node = Node.bind(context, handle);

        node.setName(new StringName("renamed"));

        assertEquals(new StringName("renamed"), node.getName());
        ScriptedEngine.CallRecord observed = engine.onlyCallTo(SET_NAME);
        assertEquals(handle, observed.objectHandle());
        assertEquals(
                List.of(Variant.of(new StringName("renamed"))), observed.arguments());
    }

    @Test
    @Covers(PROPERTIES)
    public void anExtensionPropertyRoundTripsThroughTheGeneratedTrampoline() {
        FoundryClassDescriptor descriptor =
                ConformanceFixture.classDescriptor(ConformanceFixture.SCENE_CLASS);
        ConformanceSpinner spinner = bindSpinner(descriptor);

        descriptor.access().setProperty(spinner, "speed", 12.5);

        assertEquals(12.5, (Double) descriptor.access().getProperty(spinner, "speed"), 0.0);
        assertEquals(12.5, spinner.speed(), 0.0);
    }

    @Test
    @Covers(SIGNALS)
    public void aGeneratedSignalDeliversDecodedArgumentsToItsListener() {
        long handle = engine.declareObject("Node");
        long childHandle = engine.declareObject("Node");
        FoundrySignal backing = new FoundrySignal();
        engine.respondWith(CHILD_ENTERED_TREE, Variant.ofSignal(backing));
        Node node = Node.bind(context, handle);
        Node child = Node.bind(context, childHandle);
        List<FoundryObject> observed = new ArrayList<>();

        FoundryTypedSignal.Of1<Node> signal = node.childEnteredTreeSignal();
        try (FoundrySignal.Connection connection = signal.connect(observed::add)) {
            signal.emit(child);
        }
        signal.emit(child);

        assertEquals(List.of(child), observed);
        assertTrue(engine.onlyCallTo(CHILD_ENTERED_TREE).arguments().isEmpty());
    }

    @Test
    @Covers(VIRTUAL_OVERRIDES)
    public void anEngineVirtualDispatchesToTheJavaOverride() {
        FoundryClassDescriptor descriptor =
                ConformanceFixture.classDescriptor(ConformanceFixture.SCENE_CLASS);
        ConformanceSpinner spinner = bindSpinner(descriptor);

        descriptor.access().invoke(spinner, "_process", new java.lang.Object[] {0.25});
        descriptor.access().invoke(spinner, "_process", new java.lang.Object[] {0.75});

        assertEquals(1.0, spinner.accumulatedDelta(), 1.0e-9);
        assertTrue(engine.calls().isEmpty());
    }

    @Test
    @Covers(UTILITY_FUNCTIONS)
    public void aGeneratedUtilityFunctionCallsWithoutAnObjectReceiver() {
        engine.respondWith("utility_functions/absi", Variant.of(7L));

        assertEquals(7L, Utilities.absi(context, -7L));

        ScriptedEngine.CallRecord observed = engine.onlyCallTo("utility_functions/absi");
        assertEquals(0L, observed.objectHandle());
        assertEquals(List.of(Variant.of(-7L)), observed.arguments());
    }

    @Test
    @Covers({SINGLETONS, OBJECT_IDENTITY})
    public void aSingletonResolvesOnceAndBindsToOneStableWrapper() {
        long handle = engine.declareSingleton("Engine", "Engine");

        games.cafecito.foundry.generated.classes.Engine first =
                games.cafecito.foundry.generated.singletons.Engine.bind(context);
        games.cafecito.foundry.generated.classes.Engine second =
                games.cafecito.foundry.generated.singletons.Engine.bind(context);

        assertSame(first, second);
        assertEquals(handle, first.objectHandle());
    }

    @Test
    @Covers(BUILT_IN_TYPES)
    public void aBuiltInTypeMethodTransportsItsReceiverAsTheFirstArgument() {
        engine.respondWith("builtin_classes/Vector2/methods/length", Variant.of(5.0));

        assertEquals(5.0, Vector2Api.length(context, new Vector2(3.0, 4.0)), 0.0);

        ScriptedEngine.CallRecord observed =
                engine.onlyCallTo("builtin_classes/Vector2/methods/length");
        assertEquals(0L, observed.objectHandle());
        assertEquals(List.of(Variant.of(new Vector2(3.0, 4.0))), observed.arguments());
    }

    @Test
    @Covers(OPERATORS)
    public void aBuiltInOperatorTransportsBothOperands() {
        engine.respondWith(
                "builtin_classes/Vector2/operators/+", Variant.of(new Vector2(4.0, 6.0)));

        assertEquals(
                new Vector2(4.0, 6.0),
                Vector2Api.add(context, new Vector2(1.0, 2.0), new Vector2(3.0, 4.0)));

        ScriptedEngine.CallRecord observed =
                engine.onlyCallTo("builtin_classes/Vector2/operators/+");
        assertEquals(
                List.of(Variant.of(new Vector2(1.0, 2.0)), Variant.of(new Vector2(3.0, 4.0))),
                observed.arguments());
    }

    @Test
    @Covers(TYPED_COLLECTIONS)
    public void aTypedCollectionRejectsAForeignElementAndSurvivesTheTransport() {
        FoundryArray<StringName> groups = new FoundryArray<>(VariantCodec.STRING_NAME);
        groups.add(new StringName("enemies"));
        FoundryDictionary<String, Long> scores =
                new FoundryDictionary<>(VariantCodec.STRING, VariantCodec.INTEGER);
        scores.put("enemies", 3L);
        long handle = engine.declareObject("Node");
        engine.respondWith(GET_GROUPS, Variant.of(groups));
        Node node = Node.bind(context, handle);

        assertThrows(
                VariantConversionException.class, () -> groups.addVariant(Variant.of(17L)));
        assertThrows(
                VariantConversionException.class, () -> scores.putVariants(Variant.of(1L),
                        Variant.of(2L)));
        assertEquals(List.of(new StringName("enemies")), node.getGroups().toList());
        assertEquals(3L, scores.get("enemies").longValue());
    }

    @Test
    @Covers(UNTYPED_COLLECTIONS)
    public void anUntypedCollectionAcceptsMixedVariantsAcrossTheTransport() {
        FoundryArray<Variant> mixed = FoundryArray.untyped();
        mixed.addVariant(Variant.of(1L));
        mixed.addVariant(Variant.of("two"));
        mixed.addVariant(Variant.nil());
        long handle = engine.declareObject("Node");
        engine.respondWith(GET_NODE_AND_RESOURCE, Variant.of(mixed));
        Node node = Node.bind(context, handle);

        FoundryArray<Variant> received = node.getNodeAndResource(new NodePath("child:property"));

        assertEquals(3, received.size());
        assertEquals(Variant.of(1L), received.get(0));
        assertEquals(Variant.of("two"), received.get(1));
        assertTrue(received.get(2).isNil());
    }

    @Test
    @Covers(PACKED_ARRAYS)
    public void aPackedArrayKeepsItsElementTypeAcrossTheTransport() {
        PackedInt32Array packed = new PackedInt32Array(new int[] {1, 2, 3});
        FoundryCallable doubler =
                FoundryCallable.fixed(
                        1,
                        arguments -> {
                            PackedInt32Array received =
                                    (PackedInt32Array)
                                            arguments.get(0).as(VariantType.PACKED_INT32_ARRAY);
                            int[] doubled = received.toArray();
                            for (int index = 0; index < doubled.length; index++) {
                                doubled[index] *= 2;
                            }
                            return Variant.of(new PackedInt32Array(doubled));
                        });
        long callbackHandle = context.callbackRegistry().register(doubler);

        Variant result =
                context.callbackRegistry().invoke(callbackHandle, List.of(Variant.of(packed)));

        assertEquals(VariantType.PACKED_INT32_ARRAY, Variant.of(packed).type());
        assertEquals(VariantType.PACKED_INT32_ARRAY, result.type());
        assertArrayEquals(
                new int[] {2, 4, 6},
                ((PackedInt32Array) result.as(VariantType.PACKED_INT32_ARRAY)).toArray());
        assertEquals(Integer.valueOf(2), packed.get(1));
    }

    @Test
    @Covers(CALLABLES)
    public void aCallableIsInvocableLocallyAndTransportableAsAnArgument() {
        long handle = engine.declareObject("Node");
        engine.respondWith(CONNECT, Variant.of(0L));
        Node node = Node.bind(context, handle);
        FoundryCallable callable =
                FoundryCallable.fixed(1, arguments -> Variant.of(arguments.get(0).asLong() * 2L));

        long callbackHandle = context.callbackRegistry().register(callable);
        Variant result = context.callbackRegistry().invoke(callbackHandle, List.of(Variant.of(21L)));
        node.connect(new StringName("ready"), callable);

        assertEquals(42L, result.asLong());
        assertEquals(1, callable.arity());
        assertFalse(callable.isVariadic());
        ScriptedEngine.CallRecord observed = engine.onlyCallTo(CONNECT);
        assertSame(callable, observed.arguments().get(1).asCallable());
    }

    @Test
    @Covers(OBJECT_IDENTITY)
    public void oneEngineHandleBindsToExactlyOneWrapperPerContext() {
        long first = engine.declareObject("Node");
        long second = engine.declareObject("Node");

        Node firstWrapper = Node.bind(context, first);
        Node sameWrapper = Node.bind(context, first);
        Node otherWrapper = Node.bind(context, second);

        assertSame(firstWrapper, sameWrapper);
        assertNotSame(firstWrapper, otherWrapper);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        context.bind(
                                first,
                                ObjectOwnership.BORROWED,
                                ConformanceSpinner.class,
                                (boundContext, lease) ->
                                        new ConformanceSpinner(boundContext, lease)));
    }

    @Test
    @Covers(OWNERSHIP)
    public void ownershipDecidesWhetherJavaEverReleasesTheEngineReference() {
        long borrowed = engine.declareObject("Node");
        long counted = engine.declareObject(ConformanceFixture.SCENE_CLASS);
        long owned = engine.declareInstantiable("Node", "Node");

        Node borrowedWrapper = Node.bind(context, borrowed);
        ConformanceSpinner countedWrapper =
                context.bind(
                        counted,
                        ObjectOwnership.REFERENCE_COUNTED,
                        ConformanceSpinner.class,
                        ConformanceSpinner::new);
        Node ownedWrapper = Node.create(context);
        borrowedWrapper.close();
        countedWrapper.close();
        countedWrapper.close();
        ownedWrapper.close();

        assertEquals(0L, engine.retainCount(borrowed));
        assertEquals(0L, engine.releaseCount(borrowed));
        assertEquals(1L, engine.retainCount(counted));
        assertEquals(1L, engine.releaseCount(counted));
        assertEquals(0L, engine.retainCount(owned));
        assertEquals(1L, engine.releaseCount(owned));
    }

    @Test
    @Covers(ENGINE_CALL_ERRORS)
    public void anEngineCallErrorSurfacesAsATypedFoundryCallException() {
        long handle = engine.declareObject("Node");
        engine.respondWithError(SET_NAME, FoundryCallError.INVALID_ARGUMENT, 0, "StringName");
        Node node = Node.bind(context, handle);

        FoundryCallException failure =
                assertThrows(
                        FoundryCallException.class,
                        () -> node.setName(new StringName("renamed")));

        assertTrue(failure.methodIdentity().startsWith(SET_NAME + "#"));
        assertEquals(FoundryCallError.INVALID_ARGUMENT, failure.callError());
        assertEquals(0, failure.argumentIndex());
        assertEquals("StringName", failure.expectedType());
    }

    @Test
    @Covers(EXCEPTIONS)
    public void anUnknownTrampolineMemberFailsInJavaWithoutReachingTheEngine() {
        FoundryClassDescriptor descriptor =
                ConformanceFixture.classDescriptor(ConformanceFixture.SCENE_CLASS);
        ConformanceSpinner spinner = bindSpinner(descriptor);

        IllegalArgumentException method =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                descriptor.access()
                                        .invoke(
                                                spinner,
                                                "absent_method",
                                                new java.lang.Object[] {}));
        IllegalArgumentException property =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> descriptor.access().getProperty(spinner, "absent_property"));

        assertEquals("Unknown method: absent_method", method.getMessage());
        assertEquals("Unknown property: absent_property", property.getMessage());
        assertTrue(engine.calls().isEmpty());
        assertTrue(engine.reportedExceptions().isEmpty());
    }

    private ConformanceSpinner bindSpinner(FoundryClassDescriptor descriptor) {
        long handle = engine.declareObject(ConformanceFixture.SCENE_CLASS);
        return context.bind(
                handle,
                ObjectOwnership.BORROWED,
                ConformanceSpinner.class,
                (boundContext, lease) ->
                        (ConformanceSpinner) descriptor.access().construct(boundContext, lease));
    }
}
