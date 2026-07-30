// Standalone consumer build for the engine-loaded conformance gate. It resolves Foundry-Java from
// the bootstrap repository published by the commit under test, exactly as a third-party project
// would, and produces the one annotated module JAR the exported acceptance project consumes.
allprojects {
    group = "games.cafecito.foundry.acceptance"
    version = providers.gradleProperty("foundryVersion").getOrElse("0.1.0")
}
