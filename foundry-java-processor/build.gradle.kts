import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test

dependencies {
    implementation(project(":foundry-java-annotations"))
}

val processorJar = tasks.named<Jar>("jar")

tasks.withType<Test>().configureEach {
    dependsOn(processorJar)
}
