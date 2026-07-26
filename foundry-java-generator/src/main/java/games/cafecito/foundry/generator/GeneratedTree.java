package games.cafecito.foundry.generator;

import games.cafecito.foundry.api.model.ApiInputException;
import games.cafecito.foundry.api.model.CompatibilityManifest;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Immutable generated source tree with its exact coverage and compatibility manifest. */
public final class GeneratedTree {
    private final Map<String, String> sources;
    private final Set<String> coveredIdentities;
    private final CompatibilityManifest manifest;
    private final Map<String, String> descriptorCatalog;

    GeneratedTree(
            Map<String, String> sources,
            Set<String> coveredIdentities,
            CompatibilityManifest manifest,
            Map<String, String> descriptorCatalog) {
        this.sources = Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(sources)));
        this.coveredIdentities = Collections.unmodifiableSet(new TreeSet<>(coveredIdentities));
        this.manifest = manifest;
        this.descriptorCatalog =
                Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(descriptorCatalog)));
    }

    public Map<String, String> sources() {
        return sources;
    }

    public Set<String> coveredIdentities() {
        return coveredIdentities;
    }

    public CompatibilityManifest manifest() {
        return manifest;
    }

    public Map<String, String> descriptorCatalog() {
        return descriptorCatalog;
    }

    public Map<String, String> sha256ByPath() {
        Map<String, String> hashes = new LinkedHashMap<>();
        sources.forEach(
                (path, source) ->
                        hashes.put(path, sha256(source.getBytes(StandardCharsets.UTF_8))));
        return Collections.unmodifiableMap(hashes);
    }

    public void writeTo(Path root) {
        if (Files.exists(root) && !Files.isDirectory(root)) {
            throw new ApiInputException("Generated output must be a directory: " + root + ".");
        }
        try {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Files.createDirectories(normalizedRoot);
            try (var existing = Files.list(normalizedRoot)) {
                if (existing.findAny().isPresent()) {
                    throw new ApiInputException(
                            "Generated output directory must be empty: " + normalizedRoot + ".");
                }
            }
            for (var source : sources.entrySet()) {
                Path relative = Path.of(source.getKey());
                Path destination = normalizedRoot.resolve(relative).normalize();
                if (relative.isAbsolute() || !destination.startsWith(normalizedRoot)) {
                    throw new ApiInputException(
                            "Generated source path escapes the output root: "
                                    + source.getKey()
                                    + ".");
                }
                Files.createDirectories(destination.getParent());
                Files.writeString(destination, source.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            throw new ApiInputException("Could not write generated tree " + root + ".", exception);
        }
    }

    /**
     * Installs this tree through a staged directory while preserving an already byte-identical
     * output. The sibling lock makes concurrent Gradle invocations converge without exposing a
     * partially written source tree.
     */
    public void writeReplacing(Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path parent = normalizedRoot.getParent();
        if (parent == null) {
            throw new ApiInputException("Generated output must have a parent: " + root + ".");
        }
        Path staging = null;
        try {
            Files.createDirectories(parent);
            if (matches(normalizedRoot)) {
                return;
            }
            staging =
                    Files.createTempDirectory(
                            parent, "." + normalizedRoot.getFileName() + "-staging-");
            writeTo(staging);
            Path lockPath = parent.resolve("." + normalizedRoot.getFileName() + ".lock");
            try (FileChannel channel =
                            FileChannel.open(
                                    lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                if (matches(normalizedRoot)) {
                    return;
                }
                deleteRecursively(normalizedRoot);
                try {
                    Files.move(staging, normalizedRoot, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                    Files.move(staging, normalizedRoot);
                }
                staging = null;
            }
        } catch (IOException exception) {
            throw new ApiInputException(
                    "Could not replace generated tree " + normalizedRoot + ".", exception);
        } finally {
            if (staging != null) {
                try {
                    deleteRecursively(staging);
                } catch (IOException ignored) {
                    // Preserve the primary generation failure; Gradle clean can remove staging.
                }
            }
        }
    }

    private boolean matches(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return false;
        }
        Map<String, String> actual = new TreeMap<>();
        try (var files = Files.walk(root)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                actual.put(
                        root.relativize(path).toString().replace('\\', '/'),
                        Files.readString(path, StandardCharsets.UTF_8));
            }
        }
        return actual.equals(sources);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Every Java runtime provides SHA-256.", exception);
        }
    }
}
