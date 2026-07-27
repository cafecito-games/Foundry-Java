package games.cafecito.foundry.kotlin

import games.cafecito.foundry.runtime.FoundrySignal
import games.cafecito.foundry.runtime.FoundryTypedSignal
import games.cafecito.foundry.types.Variant
import games.cafecito.foundry.types.VariantCodec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalsTest {
    @Test
    fun `raw listener receives immutable arguments on the emitting thread`() {
        val signal = FoundrySignal()
        var observed = emptyList<Variant>()
        var callbackThread = -1L
        val connection =
            signal.listen { arguments ->
                observed = arguments
                callbackThread = Thread.currentThread().id
            }
        val emittingThread = Thread.currentThread().id

        signal.emit(Variant.of("coffee"), Variant.of(2L))

        assertEquals(listOf(Variant.of("coffee"), Variant.of(2L)), observed)
        assertEquals(emittingThread, callbackThread)
        assertTrue(connection.isConnected)
        connection.close()
        assertFalse(connection.isConnected)
    }

    @Test
    fun `typed listeners use ordinary Kotlin lambdas through arity five`() {
        var arity0 = 0
        val signal0 = FoundryTypedSignal.Of0(FoundrySignal())
        signal0.listen { arity0 += 1 }
        signal0.emit()

        var arity1 = ""
        val signal1 = FoundryTypedSignal.Of1(FoundrySignal(), VariantCodec.STRING)
        signal1.listen { first -> arity1 = first }
        signal1.emit("one")

        var arity2 = ""
        val signal2 =
            FoundryTypedSignal.Of2(
                FoundrySignal(),
                VariantCodec.STRING,
                VariantCodec.INTEGER,
            )
        signal2.listen { first, second -> arity2 = "$first:$second" }
        signal2.emit("two", 2L)

        var arity3 = ""
        val signal3 =
            FoundryTypedSignal.Of3(
                FoundrySignal(),
                VariantCodec.STRING,
                VariantCodec.INTEGER,
                VariantCodec.BOOLEAN,
            )
        signal3.listen { first, second, third -> arity3 = "$first:$second:$third" }
        signal3.emit("three", 3L, true)

        var arity4 = ""
        val signal4 =
            FoundryTypedSignal.Of4(
                FoundrySignal(),
                VariantCodec.STRING,
                VariantCodec.INTEGER,
                VariantCodec.BOOLEAN,
                VariantCodec.FLOAT,
            )
        signal4.listen { first, second, third, fourth ->
            arity4 = "$first:$second:$third:$fourth"
        }
        signal4.emit("four", 4L, true, 4.5)

        var arity5 = ""
        val signal5 =
            FoundryTypedSignal.Of5(
                FoundrySignal(),
                VariantCodec.STRING,
                VariantCodec.INTEGER,
                VariantCodec.BOOLEAN,
                VariantCodec.FLOAT,
                VariantCodec.STRING,
            )
        val connection =
            signal5.listen { first, second, third, fourth, fifth ->
                arity5 = "$first:$second:$third:$fourth:$fifth"
            }
        signal5.emit("five", 5L, false, 5.5, "done")

        assertEquals(1, arity0)
        assertEquals("one", arity1)
        assertEquals("two:2", arity2)
        assertEquals("three:3:true", arity3)
        assertEquals("four:4:true:4.5", arity4)
        assertEquals("five:5:false:5.5:done", arity5)
        assertTrue(connection.isConnected)
    }

    @Test
    fun `signal argument records keep named component and copy semantics`() {
        val args2 = SignalArgs2("first", 2L)
        val (first, second) = args2
        assertEquals("first", first)
        assertEquals(2L, second)
        assertEquals(SignalArgs2("changed", 2L), args2.copy(first = "changed"))

        val args3 = SignalArgs3("first", 2L, true)
        assertEquals("first", args3.first)
        assertEquals(2L, args3.second)
        assertEquals(true, args3.third)

        val args4 = SignalArgs4("first", 2L, true, 4.0)
        assertEquals(4.0, args4.fourth)

        val args5 = SignalArgs5("first", 2L, true, 4.0, "fifth")
        val (_, _, _, fourth, fifth) = args5
        assertEquals(4.0, fourth)
        assertEquals("fifth", fifth)
    }
}
