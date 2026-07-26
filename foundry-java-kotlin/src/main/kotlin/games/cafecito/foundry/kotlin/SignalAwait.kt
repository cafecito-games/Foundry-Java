@file:JvmName("FoundrySignalAwait")

package games.cafecito.foundry.kotlin

import games.cafecito.foundry.runtime.FoundryObject
import games.cafecito.foundry.runtime.FoundryObjectDisposedException
import games.cafecito.foundry.runtime.FoundrySignal
import games.cafecito.foundry.runtime.FoundryTypedSignal
import games.cafecito.foundry.types.Variant
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

suspend fun FoundrySignal.await(owner: FoundryObject): List<Variant> =
    awaitSignal(owner) { listener -> listen(listener) }

suspend fun FoundryTypedSignal.Of0.await(owner: FoundryObject) {
    awaitSignal(owner) { listener ->
        listen { listener(Unit) }
    }
}

suspend fun <A> FoundryTypedSignal.Of1<A>.await(owner: FoundryObject): A =
    awaitSignal(owner) { listener -> listen(listener) }

suspend fun <A, B> FoundryTypedSignal.Of2<A, B>.await(owner: FoundryObject): SignalArgs2<A, B> =
    awaitSignal(owner) { listener ->
        listen { first, second -> listener(SignalArgs2(first, second)) }
    }

suspend fun <A, B, C> FoundryTypedSignal.Of3<A, B, C>.await(owner: FoundryObject): SignalArgs3<A, B, C> =
    awaitSignal(owner) { listener ->
        listen { first, second, third -> listener(SignalArgs3(first, second, third)) }
    }

suspend fun <A, B, C, D> FoundryTypedSignal.Of4<A, B, C, D>.await(owner: FoundryObject): SignalArgs4<A, B, C, D> =
    awaitSignal(owner) { listener ->
        listen { first, second, third, fourth ->
            listener(SignalArgs4(first, second, third, fourth))
        }
    }

suspend fun <A, B, C, D, E> FoundryTypedSignal.Of5<A, B, C, D, E>.await(
    owner: FoundryObject,
): SignalArgs5<A, B, C, D, E> =
    awaitSignal(owner) { listener ->
        listen { first, second, third, fourth, fifth ->
            listener(SignalArgs5(first, second, third, fourth, fifth))
        }
    }

private suspend fun <T> awaitSignal(
    owner: FoundryObject,
    connect: ((T) -> Unit) -> FoundrySignal.Connection,
): T =
    suspendCancellableCoroutine { continuation ->
        val registrations = AwaitRegistrations()
        val connection =
            connect { value ->
                registrations.tryTerminate {
                    continuation.resumeValue(value)
                }
            }
        registrations.publishConnection(connection)
        continuation.invokeOnCancellation {
            registrations.tryTerminate {}
        }
        val invalidation =
            owner.onInvalidated {
                val failure = owner.disposedFailure()
                registrations.tryTerminate {
                    continuation.resumeFailure(failure)
                }
            }
        registrations.publishInvalidation(invalidation)
    }

private class AwaitRegistrations {
    private val terminal = AtomicBoolean()
    private val connection = AtomicReference<AutoCloseable?>()
    private val invalidation = AtomicReference<AutoCloseable?>()

    fun publishConnection(value: AutoCloseable) {
        publish(connection, value)
    }

    fun publishInvalidation(value: AutoCloseable) {
        publish(invalidation, value)
    }

    fun tryTerminate(action: () -> Unit) {
        if (terminal.compareAndSet(false, true)) {
            connection.getAndSet(null)?.close()
            invalidation.getAndSet(null)?.close()
            action()
        }
    }

    private fun publish(
        slot: AtomicReference<AutoCloseable?>,
        value: AutoCloseable,
    ) {
        check(slot.compareAndSet(null, value)) {
            "Await registration was published more than once."
        }
        if (terminal.get() && slot.compareAndSet(value, null)) {
            value.close()
        }
    }
}

private fun FoundryObject.disposedFailure(): FoundryObjectDisposedException =
    try {
        objectHandle()
        error("Foundry invalidation callback ran while the owner remained alive.")
    } catch (failure: FoundryObjectDisposedException) {
        failure
    }

@OptIn(InternalCoroutinesApi::class)
private fun <T> CancellableContinuation<T>.resumeValue(value: T) {
    val token = tryResume(value)
    if (token != null) {
        completeResume(token)
    }
}

@OptIn(InternalCoroutinesApi::class)
private fun <T> CancellableContinuation<T>.resumeFailure(failure: Throwable) {
    val token = tryResumeWithException(failure)
    if (token != null) {
        completeResume(token)
    }
}
