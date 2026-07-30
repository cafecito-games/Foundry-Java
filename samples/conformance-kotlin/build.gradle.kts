plugins {
    id("org.jetbrains.kotlin.jvm")
}

val publishedFoundryVersion: String =
    providers.gradleProperty("foundryVersion").getOrElse("0.1.0")

kotlin {
    jvmToolchain(17)
    sourceSets.named("test") { kotlin.srcDir("src/conformance/kotlin") }
}

dependencies {
    // The Kotlin path is convenience over the same published Java API. It reuses the Java sample
    // extension module so a Java-only consumer never needs Kotlin to run the matrix.
    api(project(":conformance-java"))
    api("games.cafecito.foundry:foundry-java-kotlin:$publishedFoundryVersion")
    testImplementation("junit:junit:4.13.2")
}
