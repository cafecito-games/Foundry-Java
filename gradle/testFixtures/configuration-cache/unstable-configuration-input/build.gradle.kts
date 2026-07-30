plugins {
    base
}

// This build reads a file at configuration time that it then rewrites during execution, so the
// fingerprint stored by run 1 is already stale by the time run 2 configures. The entry stores
// cleanly and run 1 passes; only run 2 can observe that the cache is discarded on every build. That
// is the failure this fixture proves the reuse proof still detects.
val unstableInput = layout.projectDirectory.file("unstable-configuration-input.txt")
val observedGeneration = providers.fileContents(unstableInput).asText.get().trim().toLong()

val rewriteUnstableInput = tasks.register("rewriteUnstableInput") {
    val target = unstableInput.asFile
    val nextGeneration = observedGeneration + 1
    outputs.upToDateWhen { false }
    doLast {
        target.writeText("$nextGeneration\n")
    }
}

tasks.named("check") {
    dependsOn(rewriteUnstableInput)
}
