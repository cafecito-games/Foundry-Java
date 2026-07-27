@file:JvmName("FoundryRegistration")

package games.cafecito.foundry.kotlin

import games.cafecito.foundry.runtime.FoundryModuleProvider
import games.cafecito.foundry.runtime.FoundryRegistryBootstrap

class FoundryRegistryBuilder {
    private val providers = mutableListOf<FoundryModuleProvider>()

    fun provider(provider: FoundryModuleProvider) {
        providers += provider
    }

    internal fun snapshot(): List<FoundryModuleProvider> = java.util.List.copyOf(providers)
}

fun foundryRegistry(configure: FoundryRegistryBuilder.() -> Unit): FoundryRegistryBootstrap {
    val builder = FoundryRegistryBuilder().apply(configure)
    return FoundryRegistryBootstrap(builder.snapshot())
}
