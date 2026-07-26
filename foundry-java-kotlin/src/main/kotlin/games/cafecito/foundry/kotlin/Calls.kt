@file:JvmName("FoundryCalls")

package games.cafecito.foundry.kotlin

import games.cafecito.foundry.runtime.FoundryBindingContext
import games.cafecito.foundry.types.Variant
import games.cafecito.foundry.types.VariantCodec

class FoundryCallArguments {
    private val arguments = mutableListOf<Variant>()

    fun variant(value: Variant) {
        arguments += value
    }

    fun <T> value(
        value: T,
        codec: VariantCodec<T>,
    ) {
        arguments += codec.encode(value)
    }

    internal fun snapshot(): List<Variant> = java.util.List.copyOf(arguments)
}

fun FoundryBindingContext.call(
    objectHandle: Long,
    methodIdentity: String,
    arguments: FoundryCallArguments.() -> Unit,
): Variant {
    val callArguments = FoundryCallArguments().apply(arguments)
    return call(objectHandle, methodIdentity, callArguments.snapshot())
}

fun <T> FoundryBindingContext.call(
    objectHandle: Long,
    methodIdentity: String,
    resultCodec: VariantCodec<T>,
    arguments: FoundryCallArguments.() -> Unit,
): T = resultCodec.decode(call(objectHandle, methodIdentity, arguments))
