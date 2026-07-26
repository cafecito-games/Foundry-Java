package games.cafecito.foundry.kotlin

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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

            signal.emit(Variant.of("ready"), Variant.of(2L))

            assertEquals(listOf(Variant.of("ready"), Variant.of(2L)), result.await())
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

            result.cancelAndJoin()
            signal.emit("late")

            assertTrue(result.isCancelled)
            assertEquals(0, decodes.get())
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

    @Test
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

    @Test
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
