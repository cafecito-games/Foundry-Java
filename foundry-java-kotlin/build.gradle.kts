plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":foundry-java-runtime"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.named<Test>("test") {
    dependsOn(
        tasks.named("generatePomFileForMavenJavaPublication"),
        tasks.named("generateMetadataFileForMavenJavaPublication"),
    )
}
