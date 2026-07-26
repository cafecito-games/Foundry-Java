package games.cafecito.foundry.processor;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
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
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
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
        return compile(
                sources,
                moduleName,
                processors,
                Files.createTempDirectory("foundry-processor-test-"),
                name -> false);
    }

    static Result compile(Map<String, String> sources, String moduleName, Path output)
            throws IOException {
        return compile(
                sources,
                moduleName,
                List.of(new FoundryExtensionProcessor()),
                output,
                name -> false);
    }

    static Result compile(
            Map<String, String> sources,
            String moduleName,
            List<? extends Processor> processors,
            Path output,
            Predicate<String> failOutput)
            throws IOException {
        return compile(sources, moduleName, processors, output, failOutput, List.of(), List.of());
    }

    static Result compileWithOptions(
            Map<String, String> sources, String moduleName, List<String> extraOptions)
            throws IOException {
        return compile(
                sources,
                moduleName,
                List.of(new FoundryExtensionProcessor()),
                Files.createTempDirectory("foundry-processor-test-"),
                name -> false,
                extraOptions,
                List.of());
    }

    static Result compileWithClasspath(
            Map<String, String> sources, String moduleName, List<Path> additionalClasspath)
            throws IOException {
        return compile(
                sources,
                moduleName,
                List.of(new FoundryExtensionProcessor()),
                Files.createTempDirectory("foundry-processor-test-"),
                name -> false,
                List.of(),
                additionalClasspath);
    }

    private static Result compile(
            Map<String, String> sources,
            String moduleName,
            List<? extends Processor> processors,
            Path output,
            Predicate<String> failOutput,
            List<String> extraOptions,
            List<Path> additionalClasspath)
            throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "processor tests require a JDK");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path classes = Files.createDirectories(output.resolve("classes"));
        Path generated = Files.createDirectories(output.resolve("generated"));
        try (StandardJavaFileManager standardFileManager =
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            standardFileManager.setLocationFromPaths(
                    StandardLocation.CLASS_OUTPUT, List.of(classes));
            standardFileManager.setLocationFromPaths(
                    StandardLocation.SOURCE_OUTPUT, List.of(generated));
            JavaFileManager fileManager = failingFileManager(standardFileManager, failOutput);
            List<String> options = new ArrayList<>();
            String classpath =
                    Stream.concat(
                                    Stream.of(System.getProperty("java.class.path")),
                                    additionalClasspath.stream().map(Path::toString))
                            .collect(java.util.stream.Collectors.joining(File.pathSeparator));
            options.addAll(List.of("--release", "17", "-classpath", classpath));
            options.addAll(extraOptions);
            if (moduleName != null) {
                options.add("-Afoundry.module=" + moduleName);
            }
            List<JavaFileObject> units =
                    sources.entrySet().stream()
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
                    output,
                    List.copyOf(sources.keySet()));
        }
    }

    static void resetProcessorOutputs(Path output) throws IOException {
        Path generated = output.resolve("generated");
        if (Files.exists(generated)) {
            try (Stream<Path> paths = Files.walk(generated)) {
                for (Path path :
                        paths.sorted(Comparator.reverseOrder())
                                .filter(path -> !path.equals(generated))
                                .toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(generated);
        Path classes = output.resolve("classes");
        if (!Files.exists(classes)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(classes)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String relative = classes.relativize(path).toString().replace('\\', '/');
                if (relative.contains("_FoundryTrampoline")
                        || relative.startsWith("games/cafecito/foundry/generated/")
                        || relative.startsWith("META-INF/foundry-java/modules/")
                        || relative.startsWith("META-INF/proguard/foundry-java-")) {
                    Files.delete(path);
                }
            }
        }
    }

    private static JavaFileManager failingFileManager(
            StandardJavaFileManager delegate, Predicate<String> failOutput) {
        return new ForwardingJavaFileManager<>(delegate) {
            @Override
            public JavaFileObject getJavaFileForOutput(
                    Location location,
                    String className,
                    JavaFileObject.Kind kind,
                    FileObject sibling)
                    throws IOException {
                if (failOutput.test(className)) {
                    throw new IOException("injected output failure for " + className);
                }
                return super.getJavaFileForOutput(location, className, kind, sibling);
            }

            @Override
            public FileObject getFileForOutput(
                    Location location, String packageName, String relativeName, FileObject sibling)
                    throws IOException {
                if (failOutput.test(relativeName)) {
                    throw new IOException("injected output failure for " + relativeName);
                }
                return super.getFileForOutput(location, packageName, relativeName, sibling);
            }
        };
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
            Path outputDirectory,
            List<String> inputOrder) {
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
