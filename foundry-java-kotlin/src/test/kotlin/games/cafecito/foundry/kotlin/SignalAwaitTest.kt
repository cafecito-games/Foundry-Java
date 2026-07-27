package games.cafecito.foundry.kotlin

import games.cafecito.foundry.runtime.FoundryObject
import games.cafecito.foundry.runtime.FoundryObjectDisposedException
import games.cafecito.foundry.runtime.FoundrySignal
import games.cafecito.foundry.runtime.FoundryTypedSignal
import games.cafecito.foundry.runtime.ObjectOwnership
import games.cafecito.foundry.types.Variant
import games.cafecito.foundry.types.VariantCodec
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SignalAwaitTest {
    @Test
    fun `raw await returns one immutable argument snapshot`() =
        runTest {
            val fixture = liveOwner()
            val signal = FoundrySignal()
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    signal.await(fixture.owner)
                }
            assertEquals(1, invalidationListenerCount(fixture.owner))

            signal.emit(Variant.of("ready"), Variant.of(2L))

            assertEquals(listOf(Variant.of("ready"), Variant.of(2L)), result.await())
            assertEquals(0, invalidationListenerCount(fixture.owner))
        }

    @Test
    fun `typed await returns frozen result shapes through arity five`() =
        runTest {
            val fixture = liveOwner()

            val signal0 = FoundryTypedSignal.Of0(FoundrySignal())
            val result0 = async(start = CoroutineStart.UNDISPATCHED) { signal0.await(fixture.owner) }
            signal0.emit()
            assertEquals(Unit, result0.await())

            val signal1 = FoundryTypedSignal.Of1(FoundrySignal(), VariantCodec.STRING)
            val result1 = async(start = CoroutineStart.UNDISPATCHED) { signal1.await(fixture.owner) }
            signal1.emit("one")
            assertEquals("one", result1.await())

            val signal2 =
                FoundryTypedSignal.Of2(
                    FoundrySignal(),
                    VariantCodec.STRING,
                    VariantCodec.INTEGER,
                )
            val result2 = async(start = CoroutineStart.UNDISPATCHED) { signal2.await(fixture.owner) }
            signal2.emit("two", 2L)
            assertEquals(SignalArgs2("two", 2L), result2.await())

            val signal3 =
                FoundryTypedSignal.Of3(
                    FoundrySignal(),
                    VariantCodec.STRING,
                    VariantCodec.INTEGER,
                    VariantCodec.BOOLEAN,
                )
            val result3 = async(start = CoroutineStart.UNDISPATCHED) { signal3.await(fixture.owner) }
            signal3.emit("three", 3L, true)
            assertEquals(SignalArgs3("three", 3L, true), result3.await())

            val signal4 =
                FoundryTypedSignal.Of4(
                    FoundrySignal(),
                    VariantCodec.STRING,
                    VariantCodec.INTEGER,
                    VariantCodec.BOOLEAN,
                    VariantCodec.FLOAT,
                )
            val result4 = async(start = CoroutineStart.UNDISPATCHED) { signal4.await(fixture.owner) }
            signal4.emit("four", 4L, true, 4.5)
            assertEquals(SignalArgs4("four", 4L, true, 4.5), result4.await())

            val signal5 =
                FoundryTypedSignal.Of5(
                    FoundrySignal(),
                    VariantCodec.STRING,
                    VariantCodec.INTEGER,
                    VariantCodec.BOOLEAN,
                    VariantCodec.FLOAT,
                    VariantCodec.STRING,
                )
            val result5 = async(start = CoroutineStart.UNDISPATCHED) { signal5.await(fixture.owner) }
            signal5.emit("five", 5L, false, 5.5, "done")
            assertEquals(SignalArgs5("five", 5L, false, 5.5, "done"), result5.await())
        }

    @Test
    fun `cancellation disconnects before a later signal emission`() =
        runTest {
            val fixture = liveOwner()
            val decodes = AtomicInteger()
            val codec = countingCodec(VariantCodec.STRING, decodes)
            val signal = FoundryTypedSignal.Of1(FoundrySignal(), codec)
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    signal.await(fixture.owner)
                }
            assertEquals(1, invalidationListenerCount(fixture.owner))

            result.cancelAndJoin()
            signal.emit("late")

            assertTrue(result.isCancelled)
            assertEquals(0, decodes.get())
            assertEquals(0, invalidationListenerCount(fixture.owner))
        }

    @Test
    fun `object invalidation fails with the Java runtime disposed exception`() =
        runTest {
            supervisorScope {
                val fixture = liveOwner()
                val signal = FoundrySignal()
                val result =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        signal.await(fixture.owner)
                    }

                fixture.context.invalidateObject(7)

                assertFailsWith<FoundryObjectDisposedException> { result.await() }
            }
        }

    @Test
    fun `context invalidation fails with the Java runtime disposed exception`() =
        runTest {
            supervisorScope {
                val fixture = liveOwner()
                val signal = FoundrySignal()
                val result =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        signal.await(fixture.owner)
                    }

                fixture.context.close()

                assertFailsWith<FoundryObjectDisposedException> { result.await() }
            }
        }

    @Test
    fun `already dead owner fails during synchronous invalidation registration`() =
        runTest {
            supervisorScope {
                val fixture = liveOwner()
                fixture.owner.close()
                val signal = FoundrySignal()

                val result =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        signal.await(fixture.owner)
                    }

                assertFailsWith<FoundryObjectDisposedException> { result.await() }
            }
        }

    @Test
    fun `repeated terminal events cannot resume an await twice`() =
        runTest {
            val fixture = liveOwner()
            val signal = FoundrySignal()
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    signal.await(fixture.owner)
                }

            signal.emit(Variant.of("first"))
            signal.emit(Variant.of("second"))
            fixture.context.invalidateObject(7)

            assertEquals(listOf(Variant.of("first")), result.await())
        }

    @Test
    fun `terminal state self closes registrations published afterward`() {
        val signalCloseCount = AtomicInteger()
        val invalidationCloseCount = AtomicInteger()
        val actions = AtomicInteger()
        val registrations = newAwaitRegistrations()

        registrations.tryTerminate { actions.incrementAndGet() }
        registrations.tryTerminate { actions.incrementAndGet() }
        registrations.publishConnection(countingCloseable(signalCloseCount))
        registrations.publishInvalidation(countingCloseable(invalidationCloseCount))

        assertEquals(1, actions.get())
        assertEquals(1, signalCloseCount.get())
        assertEquals(1, invalidationCloseCount.get())
    }

    @Test
    fun `signal termination before invalidation publication closes the late token`() =
        runTest {
            val listener = AtomicReference<(String) -> Unit>()
            val connectionCloseCount = AtomicInteger()
            val invalidationCloseCount = AtomicInteger()
            val invalidationFailureCalls = AtomicInteger()

            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    awaitWithRegistrationHooks(
                        connect = { signalListener ->
                            listener.set(signalListener)
                            countingCloseable(connectionCloseCount)
                        },
                        subscribeInvalidation = { invalidated ->
                            listener.get()("ready")
                            invalidated()
                            countingCloseable(invalidationCloseCount)
                        },
                        invalidationFailure = {
                            invalidationFailureCalls.incrementAndGet()
                            AssertionError("Losing invalidation must not inspect owner lifecycle.")
                        },
                    )
                }

            assertEquals("ready", result.await())
            assertEquals(1, connectionCloseCount.get())
            assertEquals(1, invalidationCloseCount.get())
            assertEquals(0, invalidationFailureCalls.get())
        }

    @Test
    fun `typed await decodes and resumes on the signal caller thread`() {
        val fixture = liveOwner()
        val decodeThread = AtomicLong()
        val emissionThread = AtomicLong()
        val completionThread = AtomicLong()
        val completion = AtomicReference<Result<String>>()
        val completed = CountDownLatch(1)
        val codec =
            object : VariantCodec<String> {
                override fun encode(value: String): Variant = VariantCodec.STRING.encode(value)

                override fun decode(value: Variant): String {
                    decodeThread.set(Thread.currentThread().id)
                    return VariantCodec.STRING.decode(value)
                }
            }
        val signal = FoundryTypedSignal.Of1(FoundrySignal(), codec)

        suspend { signal.await(fixture.owner) }
            .startCoroutine(
                object : Continuation<String> {
                    override val context = EmptyCoroutineContext

                    override fun resumeWith(result: Result<String>) {
                        completionThread.set(Thread.currentThread().id)
                        completion.set(result)
                        completed.countDown()
                    }
                },
            )

        val executor = Executors.newSingleThreadExecutor()
        try {
            executor
                .submit {
                    emissionThread.set(Thread.currentThread().id)
                    signal.emit("ready")
                }.get(5, TimeUnit.SECONDS)

            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals("ready", completion.get().getOrThrow())
            assertEquals(emissionThread.get(), decodeThread.get())
            assertEquals(emissionThread.get(), completionThread.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @RepeatedTest(16)
    fun `signal and cancellation race to one terminal outcome`() =
        runTest {
            val fixture = liveOwner()
            val decodes = AtomicInteger()
            val signal =
                FoundryTypedSignal.Of1(
                    FoundrySignal(),
                    countingCodec(VariantCodec.STRING, decodes),
                )
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    signal.await(fixture.owner)
                }
            val barrier = CyclicBarrier(3)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val emit = executor.submit { barrierRace(barrier) { signal.emit("winner") } }
                val cancel = executor.submit { barrierRace(barrier) { result.cancel() } }
                barrier.await(5, TimeUnit.SECONDS)
                emit.get(5, TimeUnit.SECONDS)
                cancel.get(5, TimeUnit.SECONDS)

                val outcome = runCatching { result.await() }
                assertTrue(
                    outcome.getOrNull() == "winner" ||
                        outcome.exceptionOrNull() is CancellationException,
                )
                val terminalDecodes = decodes.get()
                signal.emit("late")
                assertEquals(terminalDecodes, decodes.get())
            } finally {
                executor.shutdownNow()
            }
        }

    @RepeatedTest(16)
    fun `signal and invalidation race to one terminal outcome`() =
        runTest {
            supervisorScope {
                val fixture = liveOwner()
                val decodes = AtomicInteger()
                val signal =
                    FoundryTypedSignal.Of1(
                        FoundrySignal(),
                        countingCodec(VariantCodec.STRING, decodes),
                    )
                val result =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        signal.await(fixture.owner)
                    }
                val barrier = CyclicBarrier(3)
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val emit = executor.submit { barrierRace(barrier) { signal.emit("winner") } }
                    val invalidate =
                        executor.submit {
                            barrierRace(barrier) { fixture.context.invalidateObject(7) }
                        }
                    barrier.await(5, TimeUnit.SECONDS)
                    emit.get(5, TimeUnit.SECONDS)
                    invalidate.get(5, TimeUnit.SECONDS)

                    val outcome = runCatching { result.await() }
                    assertTrue(
                        outcome.getOrNull() == "winner" ||
                            outcome.exceptionOrNull() is FoundryObjectDisposedException,
                    )
                    val terminalDecodes = decodes.get()
                    signal.emit("late")
                    assertEquals(terminalDecodes, decodes.get())
                } finally {
                    executor.shutdownNow()
                }
            }
        }

    @RepeatedTest(16)
    fun `cancellation and invalidation race then disconnect before emission`() =
        runTest {
            supervisorScope {
                val fixture = liveOwner()
                val decodes = AtomicInteger()
                val signal =
                    FoundryTypedSignal.Of1(
                        FoundrySignal(),
                        countingCodec(VariantCodec.STRING, decodes),
                    )
                val result =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        signal.await(fixture.owner)
                    }
                val barrier = CyclicBarrier(3)
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val cancel = executor.submit { barrierRace(barrier) { result.cancel() } }
                    val invalidate =
                        executor.submit {
                            barrierRace(barrier) { fixture.context.invalidateObject(7) }
                        }
                    barrier.await(5, TimeUnit.SECONDS)
                    cancel.get(5, TimeUnit.SECONDS)
                    invalidate.get(5, TimeUnit.SECONDS)

                    val failure = runCatching { result.await() }.exceptionOrNull()
                    assertTrue(
                        failure is CancellationException ||
                            failure is FoundryObjectDisposedException,
                    )
                    signal.emit("late")
                    assertEquals(0, decodes.get())
                } finally {
                    executor.shutdownNow()
                }
            }
        }

    @RepeatedTest(16)
    fun `signal cancellation and invalidation race to one terminal outcome`() =
        runTest {
            supervisorScope {
                val fixture = liveOwner()
                val decodes = AtomicInteger()
                val signal =
                    FoundryTypedSignal.Of1(
                        FoundrySignal(),
                        countingCodec(VariantCodec.STRING, decodes),
                    )
                val result =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        signal.await(fixture.owner)
                    }
                val barrier = CyclicBarrier(4)
                val executor = Executors.newFixedThreadPool(3)
                try {
                    val emit = executor.submit { barrierRace(barrier) { signal.emit("winner") } }
                    val cancel = executor.submit { barrierRace(barrier) { result.cancel() } }
                    val invalidate =
                        executor.submit {
                            barrierRace(barrier) { fixture.context.invalidateObject(7) }
                        }
                    barrier.await(5, TimeUnit.SECONDS)
                    emit.get(5, TimeUnit.SECONDS)
                    cancel.get(5, TimeUnit.SECONDS)
                    invalidate.get(5, TimeUnit.SECONDS)

                    val outcome = runCatching { result.await() }
                    assertTrue(
                        outcome.getOrNull() == "winner" ||
                            outcome.exceptionOrNull() is CancellationException ||
                            outcome.exceptionOrNull() is FoundryObjectDisposedException,
                    )
                    val terminalDecodes = decodes.get()
                    signal.emit("late")
                    assertEquals(terminalDecodes, decodes.get())
                } finally {
                    executor.shutdownNow()
                }
            }
        }

    private fun liveOwner(): OwnerFixture {
        val context = testContext()
        val owner =
            context.bind<TestObject>(
                objectHandle = 7,
                ownership = ObjectOwnership.BORROWED,
                factory = ::TestObject,
            )
        return OwnerFixture(context, owner)
    }

    private fun <T> countingCodec(
        delegate: VariantCodec<T>,
        decodes: AtomicInteger,
    ): VariantCodec<T> =
        object : VariantCodec<T> {
            override fun encode(value: T): Variant = delegate.encode(value)

            override fun decode(value: Variant): T {
                decodes.incrementAndGet()
                return delegate.decode(value)
            }

            override fun acceptsNil(): Boolean = delegate.acceptsNil()
        }

    private fun newAwaitRegistrations(): AwaitRegistrationsProbe {
        val type = Class.forName("games.cafecito.foundry.kotlin.AwaitRegistrations")
        val instance = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        return AwaitRegistrationsProbe(type, instance)
    }

    private fun countingCloseable(closeCount: AtomicInteger): AutoCloseable =
        AutoCloseable { closeCount.incrementAndGet() }

    private suspend fun <T> awaitWithRegistrationHooks(
        connect: ((T) -> Unit) -> AutoCloseable,
        subscribeInvalidation: ((() -> Unit) -> AutoCloseable),
        invalidationFailure: () -> Throwable,
    ): T =
        suspendCancellableCoroutine { continuation ->
            val method =
                Class
                    .forName("games.cafecito.foundry.kotlin.FoundrySignalAwait")
                    .declaredMethods
                    .single { candidate ->
                        candidate.name == "registerAwait" && candidate.parameterCount == 4
                    }.apply { isAccessible = true }
            try {
                method.invoke(
                    null,
                    continuation,
                    connect,
                    subscribeInvalidation,
                    invalidationFailure,
                )
            } catch (failure: InvocationTargetException) {
                throw failure.cause ?: failure
            }
        }

    private fun invalidationListenerCount(owner: TestObject): Int {
        val lease =
            FoundryObject::class.java
                .getDeclaredField("lease")
                .apply { isAccessible = true }
                .get(owner)
        val listeners =
            lease.javaClass
                .getDeclaredField("invalidationListeners")
                .apply { isAccessible = true }
                .get(lease) as Map<*, *>
        return listeners.size
    }

    private fun barrierRace(
        barrier: CyclicBarrier,
        action: () -> Unit,
    ) {
        barrier.await(5, TimeUnit.SECONDS)
        action()
    }

    private class AwaitRegistrationsProbe(
        private val type: Class<*>,
        private val instance: Any,
    ) {
        fun tryTerminate(action: () -> Unit) {
            type
                .getDeclaredMethod("tryTerminate", kotlin.jvm.functions.Function0::class.java)
                .apply { isAccessible = true }
                .invoke(instance, action)
        }

        fun publishConnection(value: AutoCloseable) {
            publish("publishConnection", value)
        }

        fun publishInvalidation(value: AutoCloseable) {
            publish("publishInvalidation", value)
        }

        private fun publish(
            method: String,
            value: AutoCloseable,
        ) {
            type
                .getDeclaredMethod(method, AutoCloseable::class.java)
                .apply { isAccessible = true }
                .invoke(instance, value)
        }
    }

    private data class OwnerFixture(
        val context: games.cafecito.foundry.runtime.FoundryBindingContext,
        val owner: TestObject,
    )
}
