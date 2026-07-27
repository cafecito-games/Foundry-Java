@file:JvmName("FoundrySignalResults")

package games.cafecito.foundry.kotlin

data class SignalArgs2<A, B>(
    val first: A,
    val second: B,
)

data class SignalArgs3<A, B, C>(
    val first: A,
    val second: B,
    val third: C,
)

data class SignalArgs4<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)

data class SignalArgs5<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
)
