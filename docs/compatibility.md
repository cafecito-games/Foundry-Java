# Compatibility

Foundry-Java targets Android and compiles Java sources with a Java 17 toolchain. Public ABI and all
generated code are Java. Kotlin is a source-level convenience layer and must not be required by Java
consumers. Use only the documented `FoundryExtension` public ABI.
