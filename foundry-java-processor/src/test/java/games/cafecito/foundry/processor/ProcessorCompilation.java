package games.cafecito.foundry.processor;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

final class ProcessorCompilation {
    private ProcessorCompilation() {}

    static Result compile(Map<String, String> sources) throws IOException {
        return compile(sources, "demo-module");
    }

    static Result compile(Map<String, String> sources, String moduleName) throws IOException {
        return compile(sources, moduleName, List.of(new FoundryExtensionProcessor()));
    }

    static Result compile(
            Map<String, String> sources, String moduleName, List<? extends Processor> processors)
            throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "processor tests require a JDK");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path output = Files.createTempDirectory("foundry-processor-test-");
        Path classes = Files.createDirectories(output.resolve("classes"));
        Path generated = Files.createDirectories(output.resolve("generated"));
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            fileManager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(generated));
            List<String> options = new ArrayList<>();
            options.addAll(
                    List.of(
                            "--release",
                            "17",
                            "-classpath",
                            System.getProperty("java.class.path")));
            if (moduleName != null) {
                options.add("-Afoundry.module=" + moduleName);
            }
            List<JavaFileObject> units =
                    sources.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .map(entry -> new Source(entry.getKey(), entry.getValue()))
                            .map(JavaFileObject.class::cast)
                            .toList();
            JavaCompiler.CompilationTask task =
                    compiler.getTask(null, fileManager, diagnostics, options, null, units);
            task.setProcessors(processors);
            boolean successful = task.call();
            return new Result(
                    successful,
                    List.copyOf(diagnostics.getDiagnostics()),
                    readTree(generated),
                    readBytes(classes),
                    output);
        }
    }

    private static Map<String, String> readTree(Path root) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path :
                    paths.filter(Files::isRegularFile)
                            .sorted(Comparator.comparing(Path::toString))
                            .toList()) {
                files.put(
                        root.relativize(path).toString().replace('\\', '/'),
                        Files.readString(path, StandardCharsets.UTF_8));
            }
        }
        return Map.copyOf(files);
    }

    private static Map<String, byte[]> readBytes(Path root) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path :
                    paths.filter(Files::isRegularFile)
                            .sorted(Comparator.comparing(Path::toString))
                            .toList()) {
                files.put(
                        root.relativize(path).toString().replace('\\', '/'),
                        Files.readAllBytes(path));
            }
        }
        return files;
    }

    record Result(
            boolean successful,
            List<Diagnostic<? extends JavaFileObject>> diagnostics,
            Map<String, String> generatedSources,
            Map<String, byte[]> classOutput,
            Path outputDirectory) {
        List<String> errorMessages() {
            List<String> messages = new ArrayList<>();
            diagnostics.stream()
                    .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                    .forEach(diagnostic -> messages.add(diagnostic.getMessage(null)));
            return List.copyOf(messages);
        }
    }

    private static final class Source extends SimpleJavaFileObject {
        private final String contents;

        private Source(String qualifiedName, String contents) {
            super(
                    URI.create(
                            "string:///" + qualifiedName.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.contents = contents;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return contents;
        }
    }
}
