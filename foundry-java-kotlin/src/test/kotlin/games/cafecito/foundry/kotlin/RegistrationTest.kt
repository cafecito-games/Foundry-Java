package games.cafecito.foundry.kotlin

import games.cafecito.foundry.runtime.FoundryModuleDescriptor
import games.cafecito.foundry.runtime.FoundryModuleProvider
import games.cafecito.foundry.runtime.FoundryRuntime
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RegistrationTest {
    @Test
    fun `explicit providers are handed to the Java bootstrap and sorted there`() {
        val alpha = TrackingProvider(descriptor("alpha", "example.Alpha"))
        val beta = TrackingProvider(descriptor("beta", "example.Beta"))

        val bootstrap =
            foundryRegistry {
                provider(beta)
                provider(alpha)
            }

        assertEquals(listOf("alpha", "beta"), bootstrap.moduleNames())
        assertEquals(listOf(alpha, beta), bootstrap.providers())
        assertEquals(1, alpha.calls.get())
        assertEquals(1, beta.calls.get())
    }

    @Test
    fun `duplicate validation is delegated unchanged to the Java bootstrap`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                foundryRegistry {
                    provider(moduleProvider("demo", "example.First"))
                    provider(moduleProvider("demo", "example.Second"))
                }
            }

        assertEquals("Duplicate Foundry module demo.", failure.message)
    }

    @Test
    fun `provenance validation is delegated unchanged to the Java bootstrap`() {
        val incompatible =
            descriptor(
                module = "demo",
                registry = "example.Demo",
                apiSha256 = "0".repeat(64),
            )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                foundryRegistry {
                    provider(FoundryModuleProvider { incompatible })
                }
            }

        assertTrue(failure.message.orEmpty().contains("uses API SHA-256"))
    }

    private fun moduleProvider(
        module: String,
        registry: String,
    ): FoundryModuleProvider = FoundryModuleProvider { descriptor(module, registry) }

    private fun descriptor(
        module: String,
        registry: String,
        apiSha256: String = FoundryRuntime.API_SHA256,
    ): FoundryModuleDescriptor =
        FoundryModuleDescriptor(
            FoundryModuleDescriptor.CURRENT_FORMAT,
            module,
            registry,
            apiSha256,
            FoundryRuntime.GENERATOR_VERSION,
            FoundryRuntime.RUNTIME_CONTRACT_VERSION,
            FoundryRuntime.BRIDGE_CONTRACT_VERSION,
            emptyList(),
        )

    private class TrackingProvider(
        private val value: FoundryModuleDescriptor,
    ) : FoundryModuleProvider {
        val calls = AtomicInteger()

        override fun descriptor(): FoundryModuleDescriptor {
            calls.incrementAndGet()
            return value
        }
    }
}
