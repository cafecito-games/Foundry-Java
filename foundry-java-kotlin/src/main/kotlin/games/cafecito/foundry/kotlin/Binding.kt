@file:JvmName("FoundryBindings")

package games.cafecito.foundry.kotlin

import games.cafecito.foundry.runtime.FoundryBindingContext
import games.cafecito.foundry.runtime.FoundryObject
import games.cafecito.foundry.runtime.ObjectLease
import games.cafecito.foundry.runtime.ObjectOwnership

inline fun <reified T : FoundryObject> FoundryBindingContext.bind(
    objectHandle: Long,
    ownership: ObjectOwnership,
    noinline factory: (FoundryBindingContext, ObjectLease) -> T,
): T =
    bind(
        objectHandle,
        ownership,
        T::class.java,
        FoundryBindingContext.ObjectFactory { context, lease -> factory(context, lease) },
    )

inline fun <reified T : FoundryObject> FoundryBindingContext.registerObjectType(
    foundryType: String,
    noinline factory: (FoundryBindingContext, ObjectLease) -> T,
) {
    registerObjectType(
        foundryType,
        T::class.java,
        FoundryBindingContext.ObjectFactory { context, lease -> factory(context, lease) },
    )
}
