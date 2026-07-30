package games.cafecito.foundry.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import javax.annotation.processing.Processor;
import org.junit.jupiter.api.Test;

class FoundryExtensionProcessorServiceTest {
    @Test
    void builtJarRegistersTheProcessorForStandardJavacDiscovery() throws IOException {
        Path processorJar;
        try (var files = Files.list(Path.of("build", "libs"))) {
            // The sources and Javadoc archives the release publishes are also built here; the
            // processor artifact is the unclassified JAR.
            List<Path> jars =
                    files.filter(path -> path.getFileName().toString().endsWith(".jar"))
                            .filter(
                                    path ->
                                            !path.getFileName().toString().contains("-sources.")
                                                    && !path.getFileName()
                                                            .toString()
                                                            .contains("-javadoc."))
                            .toList();
            assertEquals(1, jars.size(), "expected exactly one built processor JAR");
            processorJar = jars.get(0);
        }

        try (var loader =
                new URLClassLoader(
                        new java.net.URL[] {processorJar.toUri().toURL()},
                        ClassLoader.getPlatformClassLoader())) {
            List<String> providerTypes =
                    ServiceLoader.load(Processor.class, loader).stream()
                            .map(provider -> provider.type().getName())
                            .toList();
            assertEquals(List.of(FoundryExtensionProcessor.class.getName()), providerTypes);
        }
    }
}
