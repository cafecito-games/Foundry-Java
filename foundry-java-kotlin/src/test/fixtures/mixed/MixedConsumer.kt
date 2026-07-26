package games.cafecito.foundry.fixtures.mixed

import games.cafecito.foundry.kotlin.bind
import games.cafecito.foundry.kotlin.foundryReadOnlyProperty
import games.cafecito.foundry.runtime.FoundryBindingContext
import games.cafecito.foundry.runtime.ObjectOwnership

fun bindMixedObject(
    context: FoundryBindingContext,
    objectHandle: Long,
): MixedObject =
    context.bind(
        objectHandle,
        ObjectOwnership.BORROWED,
        ::MixedObject,
    )

class MixedView(
    private val objectValue: MixedObject,
) {
    val objectHandle by foundryReadOnlyProperty(objectValue::objectHandle)
}
