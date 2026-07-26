import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test

dependencies {
    implementation(project(":foundry-java-annotations"))
    testImplementation(project(":foundry-java-generator"))
    testImplementation(project(":foundry-java-runtime"))
}

val processorJar = tasks.named<Jar>("jar")
val annotationsJar = project(":foundry-java-annotations").tasks.named<Jar>("jar")

tasks.withType<Test>().configureEach {
    dependsOn(processorJar, annotationsJar)
}
