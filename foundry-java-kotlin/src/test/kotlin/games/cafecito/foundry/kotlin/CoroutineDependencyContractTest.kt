package games.cafecito.foundry.kotlin

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class CoroutineDependencyContractTest {
    @Test
    fun `coroutines are runtime metadata and absent from compile API`() {
        val moduleDirectory = Path.of(System.getProperty("user.dir"))
        val publication = moduleDirectory.resolve("build/publications/mavenJava")
        val pom = Files.readString(publication.resolve("pom-default.xml"))
        val module = Files.readString(publication.resolve("module.json"))

        assertTrue(pom.contains("<artifactId>kotlinx-coroutines-core-jvm</artifactId>"))
        assertTrue(
            Regex(
                """(?s)<dependency>.*?<artifactId>kotlinx-coroutines-core-jvm</artifactId>.*?<scope>runtime</scope>.*?</dependency>""",
            ).containsMatchIn(pom),
        )
        assertFalse(
            Regex(
                """(?s)<dependency>.*?<artifactId>kotlinx-coroutines-core-jvm</artifactId>.*?<scope>compile</scope>.*?</dependency>""",
            ).containsMatchIn(pom),
        )

        val apiElements =
            module.substringAfter("\"name\": \"apiElements\"").substringBefore(
                "\"name\": \"runtimeElements\"",
            )
        val runtimeElements = module.substringAfter("\"name\": \"runtimeElements\"")
        assertFalse(apiElements.contains("kotlinx-coroutines"))
        assertTrue(runtimeElements.contains("kotlinx-coroutines-core"))
    }
}
