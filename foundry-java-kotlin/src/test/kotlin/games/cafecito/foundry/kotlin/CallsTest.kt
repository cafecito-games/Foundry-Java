package games.cafecito.foundry.kotlin

import games.cafecito.foundry.runtime.FoundryBindingContext
import games.cafecito.foundry.runtime.FoundryCallError
import games.cafecito.foundry.runtime.FoundryCallException
import games.cafecito.foundry.runtime.FoundryEngine
import games.cafecito.foundry.types.Variant
import games.cafecito.foundry.types.VariantCodec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CallsTest {
    @Test
    fun `typed call preserves argument order then decodes the Java result`() {
        val events = mutableListOf<String>()
        val engine =
            RecordingEngine(
                events = events,
                result = FoundryEngine.CallResult.success(Variant.of("served")),
            )
        val context = FoundryBindingContext(11, engine)
        val integerCodec = recordingCodec("encode integer", VariantCodec.INTEGER, events)
        val resultCodec = recordingCodec("decode result", VariantCodec.STRING, events)

        val result =
            context.call(7, "coffee/2", resultCodec) {
                value(2L, integerCodec)
                variant(Variant.of("milk"))
            }

        assertEquals("served", result)
        assertEquals(listOf(Variant.of(2L), Variant.of("milk")), engine.arguments)
        assertEquals(1, engine.callCount)
        assertEquals(7, engine.objectHandle)
        assertEquals("coffee/2", engine.methodIdentity)
        assertEquals(listOf("encode integer", "java call", "decode result"), events)
    }

    @Test
    fun `raw call passes through the Java result and uses an immutable snapshot`() {
        val returned = Variant.of("served")
        val engine =
            RecordingEngine(
                result = FoundryEngine.CallResult.success(returned),
            )
        val context = FoundryBindingContext(11, engine)
        lateinit var captured: FoundryCallArguments

        val result =
            context.call(7, "coffee/raw") {
                captured = this
                variant(Variant.of(1L))
            }
        captured.variant(Variant.of(2L))

        assertSame(returned, result)
        assertEquals(listOf(Variant.of(1L)), engine.arguments)
        assertEquals(1, engine.callCount)
    }

    @Test
    fun `Java call exceptions pass through unchanged`() {
        val engine =
            RecordingEngine(
                result =
                    FoundryEngine.CallResult(
                        Variant.nil(),
                        FoundryCallError.INVALID_ARGUMENT,
                        0,
                        "String",
                    ),
            )
        val context = FoundryBindingContext(11, engine)

        val failure =
            assertFailsWith<FoundryCallException> {
                context.call(7, "coffee/error") {}
            }

        assertEquals(FoundryCallError.INVALID_ARGUMENT, failure.callError())
        assertEquals(0, failure.argumentIndex())
        assertEquals("String", failure.expectedType())
        assertEquals(1, engine.callCount)
    }

    @Test
    fun `codec conversion errors pass through without another Java call`() {
        val engine =
            RecordingEngine(
                result = FoundryEngine.CallResult.success(Variant.of("served")),
            )
        val context = FoundryBindingContext(11, engine)
        val failure = IllegalStateException("decode failed")
        val codec =
            object : VariantCodec<String> {
                override fun encode(value: String): Variant = Variant.of(value)

                override fun decode(value: Variant): String = throw failure
            }

        val thrown =
            assertFailsWith<IllegalStateException> {
                context.call(7, "coffee/decode", codec) {}
            }

        assertSame(failure, thrown)
        assertEquals(1, engine.callCount)
    }

    private fun <T> recordingCodec(
        event: String,
        delegate: VariantCodec<T>,
        events: MutableList<String>,
    ): VariantCodec<T> =
        object : VariantCodec<T> {
            override fun encode(value: T): Variant {
                events += event
                return delegate.encode(value)
            }

            override fun decode(value: Variant): T {
                events += event
                return delegate.decode(value)
            }

            override fun acceptsNil(): Boolean = delegate.acceptsNil()
        }

    private class RecordingEngine(
        private val events: MutableList<String> = mutableListOf(),
        private val result: FoundryEngine.CallResult,
    ) : FoundryEngine by TestEngine() {
        var arguments = emptyList<Variant>()
        var callCount = 0
        var objectHandle = 0L
        var methodIdentity = ""

        override fun call(
            contextHandle: Long,
            objectHandle: Long,
            methodIdentity: String,
            arguments: List<Variant>,
        ): FoundryEngine.CallResult {
            events += "java call"
            callCount += 1
            this.objectHandle = objectHandle
            this.methodIdentity = methodIdentity
            this.arguments = arguments
            return result
        }
    }
}
