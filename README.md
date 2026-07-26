# Foundry-Java

Foundry-Java is the Android-only, Java-first extension surface for Foundry. Java 17 defines public,
generated, and ABI-facing types; Kotlin is an optional convenience layer over those Java APIs.

## Modules

- `foundry-java-api-model` — public, Android-free ABI types including `FoundryExtension`.
- `foundry-java-annotations`, `foundry-java-generator`, and `foundry-java-processor` — Java codegen support.
- `foundry-java-runtime` and `foundry-java-test` — host-neutral runtime and test support.
- `foundry-java-android` — the only Android host/package module.
- `foundry-java-gradle-plugin` and `foundry-java-kotlin` — consumer conventions and optional Kotlin helpers.

## Build

Use JDK 17 and the checked-in wrapper:

```sh
./gradlew clean check
```

Dependency versions live in `gradle/libs.versions.toml`; resolve a deliberate update with
`./gradlew --write-locks resolveAndLockAll` and commit the changed lockfiles. Archives are configured
for reproducible file order and timestamps. `check` publishes every configured bootstrap publication
to `build/repository` and validates its POM, Gradle module metadata, and artifacts without writing to
the user Maven-local repository.

Integrations use only the public `FoundryExtension` ABI. Foundry-Java never packages, links, loads,
or redistributes `libfoundry_android.so`. The bootstrap Android AAR currently allows no classes in
`classes.jar`; later workstreams that intentionally add Foundry-Java bootstrap classes must update
the exact allowlist in `build.gradle.kts`.
