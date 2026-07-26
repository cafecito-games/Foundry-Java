# Foundry-Java contributor guidance

Foundry-Java is an Android-only, Java-first integration layer. Public and generated ABI-facing
surfaces are Java 17. Kotlin is optional convenience code over the Java API and must not become a
required ABI. Use only the public `FoundryExtension` ABI; never package, link, load, or redistribute
`libfoundry_android.so`.

Keep Android APIs and host code in `foundry-java-android`. `foundry-java-api-model`,
`foundry-java-annotations`, and `foundry-java-runtime` must remain Android-free. Run
`./gradlew clean check` before submitting changes and update dependency locks deliberately with
`./gradlew --write-locks resolveAndLockAll` when dependencies change.
