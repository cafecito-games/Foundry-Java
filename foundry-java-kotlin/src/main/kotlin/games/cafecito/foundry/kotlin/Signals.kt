@file:JvmName("FoundrySignals")

package games.cafecito.foundry.kotlin

import games.cafecito.foundry.runtime.FoundryCallable
import games.cafecito.foundry.runtime.FoundrySignal
import games.cafecito.foundry.runtime.FoundryTypedSignal
import games.cafecito.foundry.types.Variant

fun FoundrySignal.listen(listener: (List<Variant>) -> Unit): FoundrySignal.Connection =
    connect(
        FoundryCallable.variadic { arguments ->
            listener(arguments)
            Variant.nil()
        },
    )

fun FoundryTypedSignal.Of0.listen(listener: () -> Unit): FoundrySignal.Connection =
    connect(FoundryTypedSignal.Of0.Listener { listener() })

fun <A> FoundryTypedSignal.Of1<A>.listen(listener: (A) -> Unit): FoundrySignal.Connection =
    connect(FoundryTypedSignal.Of1.Listener { first -> listener(first) })

fun <A, B> FoundryTypedSignal.Of2<A, B>.listen(listener: (A, B) -> Unit): FoundrySignal.Connection =
    connect(
        FoundryTypedSignal.Of2.Listener { first, second ->
            listener(first, second)
        },
    )

fun <A, B, C> FoundryTypedSignal.Of3<A, B, C>.listen(listener: (A, B, C) -> Unit): FoundrySignal.Connection =
    connect(
        FoundryTypedSignal.Of3.Listener { first, second, third ->
            listener(first, second, third)
        },
    )

fun <A, B, C, D> FoundryTypedSignal.Of4<A, B, C, D>.listen(listener: (A, B, C, D) -> Unit): FoundrySignal.Connection =
    connect(
        FoundryTypedSignal.Of4.Listener { first, second, third, fourth ->
            listener(first, second, third, fourth)
        },
    )

fun <A, B, C, D, E> FoundryTypedSignal.Of5<A, B, C, D, E>.listen(
    listener: (A, B, C, D, E) -> Unit,
): FoundrySignal.Connection =
    connect(
        FoundryTypedSignal.Of5.Listener { first, second, third, fourth, fifth ->
            listener(first, second, third, fourth, fifth)
        },
    )
