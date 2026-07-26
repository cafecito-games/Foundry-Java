# Architecture

Foundry-Java is split by boundary rather than feature. `foundry-java-api-model` and
`foundry-java-annotations` define platform-neutral Java contracts. `foundry-java-runtime` invokes
only `FoundryExtension`; it contains no Android host classes. Android APIs and lifecycle adaptation
are isolated in `foundry-java-android`.

The generator and annotation processor produce Java-facing code. The Kotlin module consumes the Java
runtime and is optional for users. No module packages, links, loads, or redistributes native Foundry
libraries.

## FoundryExtension bridge

`foundry-java-android` packages `libfoundry_java.so` for `armeabi-v7a`, `arm64-v8a`, `x86`, and
`x86_64`. The bridge has one public FoundryExtension entry, `foundry_java_library_init`, and calls
the engine exclusively through `api/current/foundry_extension_interface.h`. It does not import
private engine headers or Android-host JNI symbols.

The JNI surface is versioned independently from the Java runtime contract. Bootstrap succeeds only
when the generated API SHA-256 and the generator, runtime, and bridge contract versions all match
the values compiled into the bridge. A mismatch leaves the bridge inactive instead of exposing a
partially initialized interface.
