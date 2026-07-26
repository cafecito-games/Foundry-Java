# Contributing to Foundry-Java

Use Java 17 and the checked-in Gradle wrapper. Run `./gradlew clean check` before opening a change.
This repository targets Android only; Java defines every generated and ABI-facing API, while Kotlin
is optional convenience code over that Java API. Add dependencies deliberately, regenerate locks with
`./gradlew --write-locks`, and commit the changed lockfiles.

Contributors may use only the public `FoundryExtension` ABI. Do not copy native host internals from
another repository and never package, link, load, or redistribute `libfoundry_android.so`.
