# Compatibility

Foundry-Java targets Android and compiles Java sources with a Java 17 toolchain. Public ABI and all
generated code are Java. Kotlin is a source-level convenience layer and must not be required by Java
consumers. Use only the documented `FoundryExtension` public ABI.

The accepted API release, exact hashes, strict schema contract, exhaustive entity classification,
and deterministic generation gates are documented in [API compatibility inputs](api-compatibility.md).
