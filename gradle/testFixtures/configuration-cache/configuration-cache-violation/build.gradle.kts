plugins {
    base
}

// Reaching back into the Project object from a task action is the canonical configuration-cache
// violation, and the class of mistake run 1 of the reuse proof exists to catch. Run 1 executes the
// real task graph with --configuration-cache-problems=fail, so it must fail here.
val holdProjectAtExecutionTime = tasks.register("holdProjectAtExecutionTime") {
    doLast {
        logger.lifecycle("configured for project {}", project.name)
    }
}

tasks.named("check") {
    dependsOn(holdProjectAtExecutionTime)
}
