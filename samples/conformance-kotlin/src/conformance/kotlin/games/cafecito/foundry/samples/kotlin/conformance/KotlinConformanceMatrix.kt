package games.cafecito.foundry.samples.kotlin.conformance

import games.cafecito.foundry.generated.Utilities
import games.cafecito.foundry.generated.builtins.Vector2Api
import games.cafecito.foundry.generated.classes.Node
import games.cafecito.foundry.generated.samplesjava.SamplesJavaRegistry
import games.cafecito.foundry.kotlin.bind
import games.cafecito.foundry.kotlin.call
import games.cafecito.foundry.kotlin.listen
import games.cafecito.foundry.kotlin.toFoundryArray
import games.cafecito.foundry.kotlin.toFoundryDictionary
import games.cafecito.foundry.kotlin.toKotlinList
import games.cafecito.foundry.kotlin.toPackedInt32Array
import games.cafecito.foundry.kotlin.variantCodec
import games.cafecito.foundry.runtime.FoundryBindingContext
import games.cafecito.foundry.runtime.FoundryCallError
import games.cafecito.foundry.runtime.FoundryCallException
import games.cafecito.foundry.runtime.FoundryCallable
import games.cafecito.foundry.runtime.FoundryClassDescriptor
import games.cafecito.foundry.runtime.FoundryInitializationLevel
import games.cafecito.foundry.runtime.FoundryObjectDisposedException
import games.cafecito.foundry.runtime.FoundryRegistryBootstrap
import games.cafecito.foundry.runtime.FoundryRegistryCoordinator
import games.cafecito.foundry.runtime.FoundrySignal
import games.cafecito.foundry.runtime.ObjectOwnership
import games.cafecito.foundry.samples.java.ConformanceCategory
import games.cafecito.foundry.samples.java.ConformanceSpinner
import games.cafecito.foundry.samples.java.Covers
import games.cafecito.foundry.samples.java.ScriptedEngine
import games.cafecito.foundry.types.FoundryArray
import games.cafecito.foundry.types.NodePath
import games.cafecito.foundry.types.StringName
import games.cafecito.foundry.types.Variant
import games.cafecito.foundry.types.VariantCodec
import games.cafecito.foundry.types.VariantConversionException
import games.cafecito.foundry.types.VariantType
import games.cafecito.foundry.types.Vector2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.EnumSet
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val CONTEXT = 1L
private const val CORE_CLASS = "ConformanceCatalog"
private const val SCENE_CLASS = "ConformanceSpinner"
private const val GET_NAME = "classes/Node/methods/get_name"
private const val SET_NAME = "classes/Node/methods/set_name"
private const val ADD_CHILD = "classes/Node/methods/add_child"
private const val GET_GROUPS = "classes/Node/methods/get_groups"
private const val GET_NODE_AND_RESOURCE = "classes/Node/methods/get_node_and_resource"
private const val CONNECT = "classes/Object/methods/connect"
private const val CHILD_ENTERED_TREE = "classes/Node/signals/child_entered_tree"

internal fun sampleBootstrap(): FoundryRegistryBootstrap =
    games.cafecito.foundry.kotlin.foundryRegistry { provider(SamplesJavaRegistry.PROVIDER) }

internal fun classDescriptor(foundryName: String): FoundryClassDescriptor =
    SamplesJavaRegistry.PROVIDER.descriptor().classes().first { it.foundryName() == foundryName }

internal fun initializedCoordinator(engine: ScriptedEngine): FoundryRegistryCoordinator {
    val coordinator = FoundryRegistryCoordinator(sampleBootstrap()) { engine }
    listOf(
        FoundryInitializationLevel.CORE,
        FoundryInitializationLevel.SERVERS,
        FoundryInitializationLevel.SCENE,
    ).forEach { level -> assertTrue(coordinator.initialize(CONTEXT, level.code())) }
    return coordinator
}

/** Behavioural conformance for the approved API surface, authored over the Kotlin helper layer. */
class KotlinApiSurfaceConformanceTest {
    private lateinit var engine: ScriptedEngine
    private lateinit var context: FoundryBindingContext

    @Before
    fun openContext() {
        engine = ScriptedEngine()
        context = FoundryBindingContext(CONTEXT, engine)
    }

    @After
    fun closeContext() {
        context.close()
    }

    @Test
    @Covers(ConformanceCategory.CALLS)
    fun aKotlinCallDslTransportsTheMethodIdentityAndArguments() {
        engine.respondWith("utility_functions/randi_range", Variant.of(5L))

        val result =
            context.call(0L, "utility_functions/randi_range#3133453818", VariantCodec.INTEGER) {
                value(1L, VariantCodec.INTEGER)
                value(6L, VariantCodec.INTEGER)
            }

        assertEquals(5L, result)
        val observed = engine.onlyCallTo("utility_functions/randi_range")
        assertEquals(0L, observed.objectHandle())
        assertEquals(listOf(Variant.of(1L), Variant.of(6L)), observed.arguments())
    }

    @Test
    @Covers(ConformanceCategory.DEFAULT_ARGUMENTS)
    fun anOmittedDefaultArgumentIsNotSynthesizedForKotlinCallers() {
        val parentHandle = engine.declareObject("Node")
        val childHandle = engine.declareObject("Node")
        engine.respondWith(ADD_CHILD, Variant.nil())
        val parent = Node.bind(context, parentHandle)
        val child = Node.bind(context, childHandle)

        parent.addChild(child)
        parent.addChild(child, true)

        val observed = engine.callsTo(ADD_CHILD)
        assertEquals(2, observed.size)
        assertEquals(1, observed[0].arguments().size)
        assertEquals(2, observed[1].arguments().size)
        assertSame(child, observed[0].arguments()[0].asObject())
    }

    @Test
    @Covers(ConformanceCategory.PROPERTIES)
    fun aClassPropertyIsReachedThroughItsGeneratedEngineMethods() {
        val handle = engine.declareObject("Node")
        engine.respondWith(SET_NAME, Variant.nil())
        engine.respondWith(GET_NAME, Variant.of(StringName("kotlin")))
        val node = Node.bind(context, handle)

        node.setName(StringName("kotlin"))

        assertEquals(StringName("kotlin"), node.getName())
        assertEquals(
            listOf(Variant.of(StringName("kotlin"))),
            engine.onlyCallTo(SET_NAME).arguments(),
        )
    }

    @Test
    @Covers(ConformanceCategory.SIGNALS)
    fun aGeneratedSignalDeliversDecodedArgumentsToAKotlinLambda() {
        val handle = engine.declareObject("Node")
        val childHandle = engine.declareObject("Node")
        engine.respondWith(CHILD_ENTERED_TREE, Variant.ofSignal(FoundrySignal()))
        val node = Node.bind(context, handle)
        val child = Node.bind(context, childHandle)
        val observed = mutableListOf<Node>()

        val signal = node.childEnteredTreeSignal()
        signal.listen { entered -> observed += entered }.use { signal.emit(child) }

        assertEquals(listOf(child), observed)
    }

    @Test
    @Covers(ConformanceCategory.VIRTUAL_OVERRIDES)
    fun anEngineVirtualDispatchesToTheJavaOverrideFromKotlin() {
        val descriptor = classDescriptor(SCENE_CLASS)
        val spinner = bindSpinner(descriptor)

        descriptor.access().invoke(spinner, "_process", arrayOf<Any>(0.5))

        assertEquals(0.5, spinner.accumulatedDelta(), 1.0e-9)
        assertTrue(engine.calls().isEmpty())
    }

    @Test
    @Covers(ConformanceCategory.UTILITY_FUNCTIONS)
    fun aGeneratedUtilityFunctionCallsWithoutAnObjectReceiver() {
        engine.respondWith("utility_functions/absi", Variant.of(9L))

        assertEquals(9L, Utilities.absi(context, -9L))
        assertEquals(0L, engine.onlyCallTo("utility_functions/absi").objectHandle())
    }

    @Test
    @Covers(ConformanceCategory.SINGLETONS, ConformanceCategory.OBJECT_IDENTITY)
    fun aSingletonResolvesOnceAndBindsToOneStableWrapper() {
        val handle = engine.declareSingleton("Engine", "Engine")

        val first = games.cafecito.foundry.generated.singletons.Engine.bind(context)
        val second = games.cafecito.foundry.generated.singletons.Engine.bind(context)

        assertSame(first, second)
        assertEquals(handle, first.objectHandle())
    }

    @Test
    @Covers(ConformanceCategory.BUILT_IN_TYPES)
    fun aBuiltInTypeMethodTransportsItsReceiverAsTheFirstArgument() {
        engine.respondWith("builtin_classes/Vector2/methods/length", Variant.of(13.0))

        assertEquals(13.0, Vector2Api.length(context, Vector2(5.0, 12.0)), 0.0)
        assertEquals(
            listOf(Variant.of(Vector2(5.0, 12.0))),
            engine.onlyCallTo("builtin_classes/Vector2/methods/length").arguments(),
        )
    }

    @Test
    @Covers(ConformanceCategory.OPERATORS)
    fun aBuiltInOperatorTransportsBothOperands() {
        engine.respondWith("builtin_classes/Vector2/operators/+", Variant.of(Vector2(4.0, 6.0)))

        assertEquals(
            Vector2(4.0, 6.0),
            Vector2Api.add(context, Vector2(1.0, 2.0), Vector2(3.0, 4.0)),
        )
        assertEquals(
            2,
            engine.onlyCallTo("builtin_classes/Vector2/operators/+").arguments().size,
        )
    }

    @Test
    @Covers(ConformanceCategory.TYPED_COLLECTIONS)
    fun aTypedCollectionBuiltFromKotlinRejectsAForeignElement() {
        val groups = listOf(StringName("enemies")).toFoundryArray(variantCodec<StringName>())
        val scores =
            mapOf("enemies" to 3L).toFoundryDictionary(
                variantCodec<String>(),
                variantCodec<Long>(),
            )
        val handle = engine.declareObject("Node")
        engine.respondWith(GET_GROUPS, Variant.of(groups))
        val node = Node.bind(context, handle)

        assertThrows(VariantConversionException::class.java) { groups.addVariant(Variant.of(17L)) }
        assertEquals(listOf(StringName("enemies")), node.getGroups().toKotlinList())
        assertEquals(3L, scores["enemies"])
    }

    @Test
    @Covers(ConformanceCategory.UNTYPED_COLLECTIONS)
    fun anUntypedCollectionAcceptsMixedVariantsAcrossTheTransport() {
        val mixed = FoundryArray.untyped()
        mixed.addVariant(Variant.of(1L))
        mixed.addVariant(Variant.of("two"))
        mixed.addVariant(Variant.nil())
        val handle = engine.declareObject("Node")
        engine.respondWith(GET_NODE_AND_RESOURCE, Variant.of(mixed))
        val node = Node.bind(context, handle)

        val received = node.getNodeAndResource(NodePath("child:property"))

        assertEquals(3, received.size())
        assertEquals(Variant.of("two"), received.get(1))
        assertTrue(received.get(2).isNil)
    }

    @Test
    @Covers(ConformanceCategory.PACKED_ARRAYS)
    fun aPackedArrayKeepsItsElementTypeAcrossTheTransport() {
        val packed = intArrayOf(1, 2, 3).toPackedInt32Array()
        val doubler =
            FoundryCallable.fixed(1) { arguments ->
                val received =
                    arguments[0].`as`(VariantType.PACKED_INT32_ARRAY)
                        as games.cafecito.foundry.types.PackedInt32Array
                Variant.of(received.toArray().map { it * 2 }.toIntArray().toPackedInt32Array())
            }
        val callbackHandle = context.callbackRegistry().register(doubler)

        val result = context.callbackRegistry().invoke(callbackHandle, listOf(Variant.of(packed)))

        assertEquals(VariantType.PACKED_INT32_ARRAY, result.type())
        assertEquals(
            listOf(2, 4, 6),
            (
                result.`as`(VariantType.PACKED_INT32_ARRAY)
                    as games.cafecito.foundry.types.PackedInt32Array
            ).toArray().toList(),
        )
    }

    @Test
    @Covers(ConformanceCategory.CALLABLES)
    fun aCallableIsInvocableLocallyAndTransportableAsAnArgument() {
        val handle = engine.declareObject("Node")
        engine.respondWith(CONNECT, Variant.of(0L))
        val node = Node.bind(context, handle)
        val callable = FoundryCallable.fixed(1) { arguments -> Variant.of(arguments[0].asLong() * 2) }

        val callbackHandle = context.callbackRegistry().register(callable)
        val result = context.callbackRegistry().invoke(callbackHandle, listOf(Variant.of(21L)))
        node.connect(StringName("ready"), callable)

        assertEquals(42L, result.asLong())
        assertSame(callable, engine.onlyCallTo(CONNECT).arguments()[1].asCallable())
    }

    @Test
    @Covers(ConformanceCategory.OBJECT_IDENTITY)
    fun oneEngineHandleBindsToExactlyOneWrapperPerContext() {
        val first = engine.declareObject("Node")
        val second = engine.declareObject("Node")

        val firstWrapper = Node.bind(context, first)
        val otherWrapper = Node.bind(context, second)

        assertSame(firstWrapper, Node.bind(context, first))
        assertNotSame(firstWrapper, otherWrapper)
    }

    @Test
    @Covers(ConformanceCategory.OWNERSHIP)
    fun ownershipDecidesWhetherKotlinEverReleasesTheEngineReference() {
        val borrowed = engine.declareObject("Node")
        val counted = engine.declareObject(SCENE_CLASS)
        val borrowedWrapper = Node.bind(context, borrowed)
        val countedWrapper =
            context.bind<ConformanceSpinner>(counted, ObjectOwnership.REFERENCE_COUNTED) {
                boundContext, lease ->
                ConformanceSpinner(boundContext, lease)
            }

        borrowedWrapper.close()
        countedWrapper.close()
        countedWrapper.close()

        assertEquals(0L, engine.releaseCount(borrowed))
        assertEquals(1L, engine.retainCount(counted))
        assertEquals(1L, engine.releaseCount(counted))
    }

    @Test
    @Covers(ConformanceCategory.ENGINE_CALL_ERRORS)
    fun anEngineCallErrorSurfacesAsATypedFoundryCallException() {
        val handle = engine.declareObject("Node")
        engine.respondWithError(SET_NAME, FoundryCallError.TOO_FEW_ARGUMENTS, 1, "StringName")
        val node = Node.bind(context, handle)

        val failure =
            assertThrows(FoundryCallException::class.java) { node.setName(StringName("renamed")) }

        assertTrue(failure.methodIdentity().startsWith("$SET_NAME#"))
        assertEquals(FoundryCallError.TOO_FEW_ARGUMENTS, failure.callError())
        assertEquals(1, failure.argumentIndex())
        assertEquals("StringName", failure.expectedType())
    }

    @Test
    @Covers(ConformanceCategory.EXCEPTIONS)
    fun anUnknownTrampolineMemberFailsInKotlinWithoutReachingTheEngine() {
        val descriptor = classDescriptor(SCENE_CLASS)
        val spinner = bindSpinner(descriptor)

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                descriptor.access().invoke(spinner, "absent_method", arrayOf<Any>())
            }

        assertEquals("Unknown method: absent_method", failure.message)
        assertTrue(engine.calls().isEmpty())
        assertTrue(engine.reportedExceptions().isEmpty())
    }

    private fun bindSpinner(descriptor: FoundryClassDescriptor): ConformanceSpinner {
        val handle = engine.declareObject(SCENE_CLASS)
        return context.bind<ConformanceSpinner>(handle, ObjectOwnership.BORROWED) {
            boundContext, lease ->
            descriptor.access().construct(boundContext, lease) as ConformanceSpinner
        }
    }
}

/** Lifecycle and hazard conformance for the Kotlin consumer path. */
class KotlinLifecycleConformanceTest {
    private lateinit var engine: ScriptedEngine

    @Before
    fun createEngine() {
        engine = ScriptedEngine()
    }

    @Test
    @Covers(ConformanceCategory.INITIALIZATION)
    fun eachInitializationLevelRegistersOnlyItsOwnDeclaredClasses() {
        val coordinator = FoundryRegistryCoordinator(sampleBootstrap()) { engine }

        assertTrue(coordinator.initialize(CONTEXT, FoundryInitializationLevel.CORE.code()))
        assertEquals(listOf(CORE_CLASS), engine.registrations())
        assertTrue(coordinator.initialize(CONTEXT, FoundryInitializationLevel.SERVERS.code()))
        assertEquals(listOf(CORE_CLASS), engine.registrations())
        assertTrue(coordinator.initialize(CONTEXT, FoundryInitializationLevel.SCENE.code()))
        assertEquals(listOf(CORE_CLASS, SCENE_CLASS), engine.registrations())
    }

    @Test
    @Covers(ConformanceCategory.DEINITIALIZATION)
    fun deinitializationUnregistersInExactReverseOrderAndCompletesTheContext() {
        val coordinator = initializedCoordinator(engine)

        listOf(
            FoundryInitializationLevel.SCENE,
            FoundryInitializationLevel.SERVERS,
            FoundryInitializationLevel.CORE,
        ).forEach { level -> coordinator.deinitialize(CONTEXT, level.code()) }

        assertEquals(listOf(SCENE_CLASS, CORE_CLASS), engine.unregistrations())
        assertTrue(coordinator.terminalCleanupComplete(CONTEXT))
        assertFalse(engine.bindingContext().isAlive)
    }

    @Test
    @Covers(ConformanceCategory.INITIALIZATION_LEVEL_MISMATCH)
    fun anOutOfOrderInitializationLevelIsRejectedWithoutRegisteringAnything() {
        val coordinator = FoundryRegistryCoordinator(sampleBootstrap()) { engine }

        assertFalse(coordinator.initialize(CONTEXT, FoundryInitializationLevel.SCENE.code()))
        assertFalse(coordinator.initialize(CONTEXT, 99))
        assertFalse(coordinator.initialize(0L, FoundryInitializationLevel.CORE.code()))

        assertEquals(emptyList<String>(), engine.registrations())
    }

    @Test
    @Covers(ConformanceCategory.OBJECT_DESTRUCTION)
    fun engineSideDestructionInvalidatesTheWrapperBeforeTheNextCall() {
        val context = FoundryBindingContext(CONTEXT, engine)
        val handle = engine.declareObject("Node")
        engine.respondWith(GET_NAME, Variant.of(StringName("before")))
        val node = Node.bind(context, handle)
        val invalidations = AtomicInteger()
        val subscription = node.onInvalidated { invalidations.incrementAndGet() }
        assertEquals(StringName("before"), node.getName())

        engine.destroyObject(handle)

        assertFalse(node.isAlive)
        assertFalse(subscription.isActive)
        assertEquals(1, invalidations.get())
        assertThrows(FoundryObjectDisposedException::class.java) { node.getName() }
        assertEquals(1, engine.callsTo(GET_NAME).size)
        context.close()
    }

    @Test
    @Covers(ConformanceCategory.CLOSE_AND_CLEANER_FALLBACK)
    fun closeReleasesExactlyOnceAndAnUnreachableWrapperStillReleases() {
        val context = FoundryBindingContext(CONTEXT, engine)
        val closedHandle = engine.declareObject(SCENE_CLASS)
        val abandonedHandle = engine.declareObject(SCENE_CLASS)
        val closed = referenceCounted(context, closedHandle)
        referenceCounted(context, abandonedHandle)

        closed.close()
        closed.close()
        awaitCleanerRelease(abandonedHandle)

        assertEquals(1L, engine.releaseCount(closedHandle))
        assertEquals(1L, engine.releaseCount(abandonedHandle))
        assertEquals(emptyList<Throwable>(), engine.reportedExceptions())
        context.close()
        assertEquals(1L, engine.releaseCount(abandonedHandle))
    }

    @Test
    @Covers(ConformanceCategory.THREAD_ATTACH_AND_DETACH)
    fun aCallbackRunsOnAnyAttachedThreadAndIsRefusedAfterDetach() {
        val coordinator = initializedCoordinator(engine)
        val context = engine.bindingContext()
        val callbackHandle =
            context.callbackRegistry().register(
                FoundryCallable.fixed(1) { arguments -> Variant.of(arguments[0].asLong() * 2) },
            )
        val argumentHandle = engine.encodeVariant(CONTEXT, Variant.of(21L))
        val observed = AtomicLong(-1L)

        val worker =
            Thread({
                observed.set(coordinator.invoke(CONTEXT, callbackHandle, longArrayOf(argumentHandle)))
            }, "foundry-kotlin-conformance-attached")
        worker.start()
        worker.join(TimeUnit.SECONDS.toMillis(10))

        assertFalse(worker.isAlive)
        assertEquals(42L, engine.decodeVariant(CONTEXT, observed.get()).asLong())
        coordinator.invalidate(CONTEXT)
        assertEquals(0L, coordinator.invoke(CONTEXT, callbackHandle, longArrayOf(argumentHandle)))
        assertFalse(context.isAlive)
    }

    @Test
    @Covers(ConformanceCategory.EXCEPTIONS_FROM_CALLBACKS)
    fun aThrowingCallbackIsReportedToTheBridgeAndReturnsNilInsteadOfPropagating() {
        val coordinator = initializedCoordinator(engine)
        val context = engine.bindingContext()
        val thrown = IllegalStateException("kotlin sample callback failure")
        val callbackHandle =
            context.callbackRegistry().register(FoundryCallable.fixed(0) { throw thrown })

        val result = coordinator.invoke(CONTEXT, callbackHandle, longArrayOf())

        assertEquals(0L, result)
        assertEquals(listOf<Throwable>(thrown), engine.reportedExceptions())
        assertTrue(context.isAlive)
    }

    private fun referenceCounted(
        context: FoundryBindingContext,
        handle: Long,
    ): ConformanceSpinner =
        context.bind<ConformanceSpinner>(handle, ObjectOwnership.REFERENCE_COUNTED) {
            boundContext, lease ->
            ConformanceSpinner(boundContext, lease)
        }

    private fun awaitCleanerRelease(handle: Long) {
        repeat(200) { attempt ->
            if (engine.releaseCount(handle) == 1L) {
                return
            }
            // Allocation pressure plus an explicit hint is the only portable way to make a
            // Cleaner-backed fallback observable; the assertion below still demands the exact
            // documented outcome of one release.
            ByteArray(1 shl 20)[0] = attempt.toByte()
            System.gc()
            Thread.sleep(10L)
        }
        assertEquals(
            "The Cleaner fallback did not release the abandoned wrapper exactly once.",
            1L,
            engine.releaseCount(handle),
        )
    }
}

/**
 * Concurrency conformance for the Kotlin consumer path.
 *
 * <p>Both hazards run repeatedly so a single lucky interleaving cannot pass for coverage.
 */
class KotlinConcurrencyConformanceTest {
    private val iterations: Int =
        System.getProperty("foundry.conformance.iterations")?.toInt() ?: 200

    @Test
    @Covers(ConformanceCategory.REENTRANT_CALLBACKS)
    fun aSameThreadReentrantCallbackCompletesBothInvocationsInOrder() {
        repeat(iterations) { iteration ->
            val engine = ScriptedEngine()
            val coordinator = initializedCoordinator(engine)
            val context = engine.bindingContext()
            val depth = AtomicInteger()
            val maximumDepth = AtomicInteger()
            val innerHandle = AtomicLong()
            innerHandle.set(
                context.callbackRegistry().register(
                    FoundryCallable.fixed(0) {
                        maximumDepth.accumulateAndGet(depth.get()) { left, right ->
                            maxOf(left, right)
                        }
                        Variant.of(7L)
                    },
                ),
            )
            val outerHandle =
                context.callbackRegistry().register(
                    FoundryCallable.fixed(0) {
                        depth.incrementAndGet()
                        val nested =
                            coordinator.invoke(CONTEXT, innerHandle.get(), longArrayOf())
                        depth.decrementAndGet()
                        Variant.of(engine.decodeVariant(CONTEXT, nested).asLong() + 1L)
                    },
                )

            val result = coordinator.invoke(CONTEXT, outerHandle, longArrayOf())

            assertEquals(
                "iteration $iteration",
                8L,
                engine.decodeVariant(CONTEXT, result).asLong(),
            )
            assertEquals("iteration $iteration", 1, maximumDepth.get())
            coordinator.invalidate(CONTEXT)
            assertTrue("iteration $iteration", coordinator.terminalCleanupComplete(CONTEXT))
        }
    }

    @Test
    @Covers(ConformanceCategory.DEINITIALIZATION_RACES)
    fun aDeinitializationRacingACallbackAlwaysReachesOneCompleteTeardown() {
        repeat(iterations) { iteration ->
            val engine = ScriptedEngine()
            val coordinator = initializedCoordinator(engine)
            val context = engine.bindingContext()
            val callbackHandle =
                context.callbackRegistry().register(FoundryCallable.fixed(0) { Variant.of(3L) })
            val start = CyclicBarrier(2)
            val escaped = AtomicReference<Throwable?>(null)
            val callbackResult = AtomicLong(-1L)

            val caller =
                Thread({
                    runCatching {
                        start.await(10, TimeUnit.SECONDS)
                        callbackResult.set(
                            coordinator.invoke(CONTEXT, callbackHandle, longArrayOf()),
                        )
                    }.onFailure { failure -> escaped.compareAndSet(null, failure) }
                }, "foundry-kotlin-caller-$iteration")
            val teardown =
                Thread({
                    runCatching {
                        start.await(10, TimeUnit.SECONDS)
                        coordinator.deinitialize(CONTEXT, FoundryInitializationLevel.CORE.code())
                    }.onFailure { failure -> escaped.compareAndSet(null, failure) }
                }, "foundry-kotlin-teardown-$iteration")
            caller.start()
            teardown.start()
            caller.join(TimeUnit.SECONDS.toMillis(30))
            teardown.join(TimeUnit.SECONDS.toMillis(30))

            val label = "iteration $iteration"
            assertFalse(label, caller.isAlive)
            assertFalse(label, teardown.isAlive)
            assertNull(label, escaped.get())
            val observed = callbackResult.get()
            assertTrue(
                "$label unexpected callback result $observed",
                observed == 0L || engine.decodeVariant(CONTEXT, observed).asLong() == 3L,
            )
            assertEquals(label, listOf(SCENE_CLASS, CORE_CLASS), engine.unregistrations())
            assertTrue(label, coordinator.terminalCleanupComplete(CONTEXT))
            assertFalse(label, context.isAlive)
            assertEquals(label, emptyList<Throwable>(), engine.reportedExceptions())
        }
    }
}

/** Proves the Kotlin matrix leaves no conformance category unmapped. */
class KotlinConformanceCoverageTest {
    private val matrix =
        listOf(
            KotlinApiSurfaceConformanceTest::class.java,
            KotlinLifecycleConformanceTest::class.java,
            KotlinConcurrencyConformanceTest::class.java,
        )

    @Test
    fun everyConformanceCategoryIsClaimedByANamedKotlinTest() {
        val claimed = EnumSet.noneOf(ConformanceCategory::class.java)
        val unannotated = mutableListOf<String>()
        matrix.forEach { testClass ->
            testClass.declaredMethods
                .filter { method -> method.getAnnotation(Test::class.java) != null }
                .forEach { method ->
                    val covers = method.getAnnotation(Covers::class.java)
                    if (covers == null || covers.value.isEmpty()) {
                        unannotated += "${testClass.simpleName}.${method.name}"
                    } else {
                        claimed.addAll(covers.value.toList())
                    }
                }
        }

        val unmapped = EnumSet.allOf(ConformanceCategory::class.java)
        unmapped.removeAll(claimed)
        assertEquals(
            "Kotlin conformance categories without a named test: $unmapped",
            emptySet<ConformanceCategory>(),
            unmapped,
        )
        assertEquals(
            "Kotlin conformance tests without a @Covers claim: $unannotated",
            emptyList<String>(),
            unannotated,
        )
    }
}
