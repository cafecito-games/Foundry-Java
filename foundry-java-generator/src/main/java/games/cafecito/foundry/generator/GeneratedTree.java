package games.cafecito.foundry.generator;

import games.cafecito.foundry.api.model.ApiInputException;
import games.cafecito.foundry.api.model.CompatibilityManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
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

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Every Java runtime provides SHA-256.", exception);
        }
    }
}
