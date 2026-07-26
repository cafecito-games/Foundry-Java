package games.cafecito.foundry.kotlin

import games.cafecito.foundry.api.FoundryExtension
import games.cafecito.foundry.runtime.FoundryRuntime

/** Kotlin convenience API layered over the Java runtime. */
fun FoundryExtension.attachToFoundry() = FoundryRuntime.attach(this)
