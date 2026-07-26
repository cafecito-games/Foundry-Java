# Architecture

Foundry-Java is split by boundary rather than feature. `foundry-java-api-model` and
`foundry-java-annotations` define platform-neutral Java contracts. `foundry-java-runtime` invokes
only `FoundryExtension`; it contains no Android host classes. Android APIs and lifecycle adaptation
are isolated in `foundry-java-android`.

The generator and annotation processor produce Java-facing code. The Kotlin module consumes the Java
runtime and is optional for users. No module packages, links, loads, or redistributes native Foundry
libraries.
