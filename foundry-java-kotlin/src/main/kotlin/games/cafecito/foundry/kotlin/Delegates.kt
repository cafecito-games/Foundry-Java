@file:JvmName("FoundryDelegates")

package games.cafecito.foundry.kotlin

import kotlin.properties.ReadOnlyProperty
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

fun <T> foundryProperty(
    getter: () -> T,
    setter: (T) -> Unit,
): ReadWriteProperty<Any?, T> =
    object : ReadWriteProperty<Any?, T> {
        override fun getValue(
            thisRef: Any?,
            property: KProperty<*>,
        ): T = getter()

        override fun setValue(
            thisRef: Any?,
            property: KProperty<*>,
            value: T,
        ) {
            setter(value)
        }
    }

fun <T> foundryReadOnlyProperty(getter: () -> T): ReadOnlyProperty<Any?, T> =
    object : ReadOnlyProperty<Any?, T> {
        override fun getValue(
            thisRef: Any?,
            property: KProperty<*>,
        ): T = getter()
    }
