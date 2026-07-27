package games.cafecito.foundry.kotlin

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DelegatesTest {
    @Test
    fun `read write delegate invokes only explicit accessors`() {
        var backing = 3L
        var reads = 0
        var writes = 0
        val holder =
            object {
                var value by
                    foundryProperty(
                        getter = {
                            reads += 1
                            backing
                        },
                        setter = {
                            writes += 1
                            backing = it
                        },
                    )
            }

        holder.value = 7L

        assertEquals(7L, holder.value)
        assertEquals(1, reads)
        assertEquals(1, writes)
    }

    @Test
    fun `read only delegate invokes its explicit accessor`() {
        var backing = 4L
        var reads = 0
        val holder =
            object {
                val doubled by
                    foundryReadOnlyProperty {
                        reads += 1
                        backing * 2
                    }
            }

        assertEquals(8L, holder.doubled)
        backing = 6L
        assertEquals(12L, holder.doubled)
        assertEquals(2, reads)
    }
}
