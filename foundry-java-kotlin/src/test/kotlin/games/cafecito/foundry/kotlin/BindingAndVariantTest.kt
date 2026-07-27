package games.cafecito.foundry.kotlin

import games.cafecito.foundry.runtime.FoundryCallable
import games.cafecito.foundry.runtime.FoundrySignal
import games.cafecito.foundry.runtime.ObjectOwnership
import games.cafecito.foundry.types.PackedStringArray
import games.cafecito.foundry.types.Variant
import games.cafecito.foundry.types.VariantCodec
import games.cafecito.foundry.types.Vector2
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BindingAndVariantTest {
    @Test
    fun `reified binding uses the requested wrapper class and explicit factory`() {
        val context = testContext()

        val wrapper =
            context.bind<TestObject>(
                objectHandle = 7,
                ownership = ObjectOwnership.BORROWED,
                factory = ::TestObject,
            )

        assertEquals(TestObject::class.java, wrapper.javaClass)
    }

    @Test
    fun `reified registration delegates wrapper selection to the Java runtime`() {
        val engine = TestEngine().apply { objectType = "CoffeeNode" }
        val context = testContext(engine)
        context.registerObjectType<TestObject>("CoffeeNode", ::TestObject)

        val wrapper =
            context.bind<TestObject>(
                objectHandle = 7,
                ownership = ObjectOwnership.BORROWED,
                factory = ::TestObject,
            )

        assertEquals(TestObject::class.java, wrapper.javaClass)
    }

    @Test
    fun `canonical codecs round trip without numeric narrowing`() {
        assertEquals(9L, Variant.of(9L).decode<Long>())
        assertEquals("coffee", "coffee".toVariant().decode<String>())
        assertFailsWith<IllegalArgumentException> { variantCodec<Int>() }
    }

    @Test
    fun `canonical codecs round trip representative value and runtime families`() {
        val context = testContext()
        val owner =
            context.bind<TestObject>(
                objectHandle = 7,
                ownership = ObjectOwnership.BORROWED,
                factory = ::TestObject,
            )
        val vector = Vector2(1.25, -4.5)
        val callable =
            FoundryCallable.unary(VariantCodec.STRING, VariantCodec.STRING) { value ->
                value.uppercase()
            }
        val signal = FoundrySignal()
        val packed = PackedStringArray(arrayOf("coffee", "ready"))

        assertEquals(vector, vector.toVariant().decode<Vector2>())
        assertEquals(owner, owner.toVariant().decode<TestObject>())
        assertEquals(callable, callable.toVariant().decode<FoundryCallable>())
        assertEquals(signal, signal.toVariant().decode<FoundrySignal>())
        assertEquals(packed, packed.toVariant().decode<PackedStringArray>())
    }

    @Test
    fun `nullable codec handles nil explicitly`() {
        val nullable = VariantCodec.STRING.nullable()

        assertNull(nullable.decode(Variant.nil()))
        assertEquals("coffee", nullable.decode(Variant.of("coffee")))
    }

    @Test
    fun `object subclass codec rejects a different wrapper subclass`() {
        val context = testContext()
        val actual =
            context.bind<OtherObject>(
                objectHandle = 8,
                ownership = ObjectOwnership.BORROWED,
                factory = ::OtherObject,
            )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                variantCodec<TestObject>().decode(Variant.of(actual))
            }

        assertTrue(failure.message.orEmpty().contains(TestObject::class.java.name))
        assertTrue(failure.message.orEmpty().contains(OtherObject::class.java.name))
    }
}
