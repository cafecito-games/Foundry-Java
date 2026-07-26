package games.cafecito.foundry.fixtures.kotlin

import games.cafecito.foundry.kotlin.SignalArgs2
import games.cafecito.foundry.kotlin.await
import games.cafecito.foundry.kotlin.bind
import games.cafecito.foundry.kotlin.call
import games.cafecito.foundry.kotlin.foundryProperty
import games.cafecito.foundry.kotlin.listen
import games.cafecito.foundry.kotlin.toFoundryArray
import games.cafecito.foundry.kotlin.toKotlinList
import games.cafecito.foundry.kotlin.toVariant
import games.cafecito.foundry.runtime.FoundryBindingContext
import games.cafecito.foundry.runtime.FoundryObject
import games.cafecito.foundry.runtime.FoundrySignal
import games.cafecito.foundry.runtime.FoundryTypedSignal
import games.cafecito.foundry.runtime.ObjectLease
import games.cafecito.foundry.runtime.ObjectOwnership
import games.cafecito.foundry.types.Variant
import games.cafecito.foundry.types.VariantCodec

class KotlinConsumer(
    context: FoundryBindingContext,
    lease: ObjectLease,
) : FoundryObject(context, lease) {
    private var label = "coffee"

    var delegatedLabel by foundryProperty({ label }, { label = it })
}

fun bindKotlinConsumer(
    context: FoundryBindingContext,
    objectHandle: Long,
): KotlinConsumer =
    context.bind(
        objectHandle,
        ObjectOwnership.BORROWED,
        ::KotlinConsumer,
    )

fun useKotlinHelpers(
    context: FoundryBindingContext,
    signal: FoundrySignal,
): List<Long> {
    signal.listen { arguments -> arguments.size }
    val result =
        context.call(0, "fixture/call", VariantCodec.INTEGER) {
            variant("coffee".toVariant())
            value(2L, VariantCodec.INTEGER)
        }
    return listOf(result).toFoundryArray(VariantCodec.INTEGER).toKotlinList()
}

suspend fun awaitTwo(
    signal: FoundryTypedSignal.Of2<String, Long>,
    owner: FoundryObject,
): SignalArgs2<String, Long> = signal.await(owner)

fun rawVariant(value: String): Variant = value.toVariant()
