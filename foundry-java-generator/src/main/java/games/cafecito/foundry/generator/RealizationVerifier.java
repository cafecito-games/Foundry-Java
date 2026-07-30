package games.cafecito.foundry.generator;

import games.cafecito.foundry.api.model.ApiInputException;
import games.cafecito.foundry.api.model.ApiInputs;
import games.cafecito.foundry.api.model.CompatibilityManifest;
import games.cafecito.foundry.api.model.FoundryApi;
import games.cafecito.foundry.api.model.FoundryApiParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Runs the engine-API parity oracle over a generated realization map and writes its evidence.
 *
 * <p>Evidence is written before any failure is raised, so continuous integration can upload the
 * realization map, the per-entity accounting, and any diff on success and on failure alike.
 */
public final class RealizationVerifier {
    /** Format marker of the pinned per-entity accounting, bumped when its grammar changes. */
    public static final String SUMMARY_FORMAT = "foundry-java-realization-summary/1";

    /** File name the binding-neutral surface manifest is published under as evidence. */
    public static final String SURFACE_MANIFEST_FILE_NAME = "foundry-java-surface-manifest.json";

    /** Diagnostic prefix of a manifest whose provenance disagrees with the accepted inputs. */
    public static final String PROVENANCE_DRIFT = "SURFACE_MANIFEST_PROVENANCE_DRIFT";

    private RealizationVerifier() {}

    /**
     * Reports every manifest provenance field that disagrees with the accepted inputs. The
     * manifest-versus-map comparison re-derives from the manifest's own provenance, so provenance
     * itself is anchored here instead.
     */
    public static List<String> provenanceDrift(
            SurfaceManifest manifest, SurfaceManifest.Provenance expected) {
        Map<String, List<String>> fields = new TreeMap<>();
        SurfaceManifest.Provenance observed = manifest.provenance();
        fields.put(
                "engine_api_version",
                List.of(expected.engineApiVersion(), observed.engineApiVersion()));
        fields.put(
                "engine_api_sha256",
                List.of(expected.engineApiSha256(), observed.engineApiSha256()));
        fields.put(
                "binding_version", List.of(expected.bindingVersion(), observed.bindingVersion()));
        fields.put(
                "generator_version",
                List.of(expected.generatorVersion(), observed.generatorVersion()));
        fields.put(
                "bridge_contract_version",
                List.of(expected.bridgeContractVersion(), observed.bridgeContractVersion()));
        List<String> drift = new ArrayList<>();
        fields.forEach(
                (field, values) -> {
                    if (!values.get(0).equals(values.get(1))) {
                        drift.add(
                                PROVENANCE_DRIFT
                                        + " field="
                                        + field
                                        + " expected="
                                        + Diagnostics.escape(values.get(0))
                                        + " observed="
                                        + Diagnostics.escape(values.get(1)));
                    }
                });
        if (manifest.schemaVersion() != SurfaceManifest.SCHEMA_VERSION) {
            drift.add(
                    PROVENANCE_DRIFT
                            + " field=schema_version expected="
                            + SurfaceManifest.SCHEMA_VERSION
                            + " observed="
                            + manifest.schemaVersion());
        }
        return List.copyOf(drift);
    }

    public static void main(String[] arguments) {
        if (arguments.length != 7) {
            throw new IllegalArgumentException(
                    "Usage: RealizationVerifier <api-directory> <realization-map> "
                            + "<generated-classes-directory> <accounting-baseline> "
                            + "<report-directory> <surface-manifest> <binding-version>");
        }
        Path apiDirectory = Path.of(arguments[0]);
        Path realizationMapPath = Path.of(arguments[1]);
        Path classesDirectory = Path.of(arguments[2]);
        Path accountingBaseline = Path.of(arguments[3]);
        Path reportDirectory = Path.of(arguments[4]);
        Path surfaceManifestPath = Path.of(arguments[5]);
        String bindingVersion = arguments[6];

        ApiInputs inputs = ApiInputs.load(apiDirectory);
        FoundryApi api = FoundryApiParser.parse(inputs);
        CompatibilityManifest manifest = CompatibilityManifest.parse(api, inputs);
        RealizationMap map = RealizationMap.parse(read(realizationMapPath));
        GeneratedSurface surface = GeneratedSurface.fromCompiledClasses(classesDirectory);
        SurfaceManifest surfaceManifest = SurfaceManifest.parse(read(surfaceManifestPath));

        List<RealizationOracle.Violation> violations =
                RealizationOracle.verify(map, manifest, surface);
        String accounting = accounting(map, surface);
        String expectedAccounting = read(accountingBaseline);
        String diff = diff(expectedAccounting, accounting);

        List<String> messages = new ArrayList<>();
        violations.forEach(violation -> messages.add(violation.message()));
        messages.addAll(surfaceManifest.disagreementsWith(map));
        messages.addAll(
                provenanceDrift(
                        surfaceManifest,
                        new SurfaceManifest.Provenance(
                                inputs.provenance().apiVersion(),
                                inputs.extensionApiSha256(),
                                bindingVersion,
                                inputs.provenance().generatorVersion(),
                                inputs.provenance().bridgeContractVersion())));
        if (!diff.isEmpty()) {
            messages.add(
                    "REALIZATION_ACCOUNTING_DRIFT baseline="
                            + accountingBaseline
                            + " review the per-entity accounting change before accepting it");
        }
        write(reportDirectory.resolve("realization-map.tsv"), map.render());
        write(reportDirectory.resolve("realization-accounting.txt"), accounting);
        copy(surfaceManifestPath, reportDirectory.resolve(SURFACE_MANIFEST_FILE_NAME));
        write(reportDirectory.resolve("realization-diff.txt"), diff.isEmpty() ? "" : diff);
        write(
                reportDirectory.resolve("realization-violations.txt"),
                messages.isEmpty() ? "" : String.join("\n", messages) + "\n");

        if (messages.isEmpty()) {
            return;
        }
        StringBuilder failure =
                new StringBuilder("The generated Java surface is not at parity with ")
                        .append(apiDirectory)
                        .append(": ")
                        .append(violations.size())
                        .append(" parity violations and ")
                        .append(messages.size() - violations.size())
                        .append(" manifest or accounting failures.");
        messages.stream()
                .limit(200)
                .forEach(message -> failure.append(System.lineSeparator()).append(message));
        if (!diff.isEmpty()) {
            failure.append(System.lineSeparator()).append(diff);
        }
        throw new ApiInputException(failure.toString());
    }

    /**
     * Renders the per-entity accounting that replaces the aggregate public API line count and
     * digest as the frozen evidence of realization.
     */
    public static String accounting(RealizationMap map, GeneratedSurface surface) {
        int realizedEntities = 0;
        int realizedMembers = 0;
        Map<String, Integer> byReason = new TreeMap<>();
        for (NonRealizationReason reason : NonRealizationReason.approved()) {
            byReason.put(reason.name(), 0);
        }
        Map<String, Integer> byCategory = new TreeMap<>();
        for (RealizationMap.Entry entry : map.entries()) {
            String category = entry.sourceIdentity().split("/", 2)[0];
            byCategory.merge(category, 1, Integer::sum);
            if (entry.isRealized()) {
                realizedEntities++;
                realizedMembers += entry.realizedMembers().size();
            } else {
                byReason.merge(entry.nonRealizationReason(), 1, Integer::sum);
            }
        }
        Set<JavaMember> claimed = new java.util.HashSet<>();
        map.entries().forEach(entry -> claimed.addAll(entry.realizedMembers()));
        int structural = 0;
        for (JavaMember member : surface.members()) {
            if (!claimed.contains(member)) {
                structural++;
            }
        }
        StringBuilder accounting = new StringBuilder(SUMMARY_FORMAT).append('\n');
        accounting
                .append("realization-map-sha256 ")
                .append(map.sha256())
                .append('\n')
                .append("source-entities ")
                .append(map.size())
                .append('\n')
                .append("realized-entities ")
                .append(realizedEntities)
                .append('\n')
                .append("non-realized-entities ")
                .append(map.size() - realizedEntities)
                .append('\n')
                .append("realized-members ")
                .append(realizedMembers)
                .append('\n')
                .append("generated-surface-members ")
                .append(surface.members().size())
                .append('\n')
                .append("structural-surface-members ")
                .append(structural)
                .append('\n');
        byCategory.forEach(
                (category, count) ->
                        accounting
                                .append("category ")
                                .append(category)
                                .append(' ')
                                .append(count)
                                .append('\n'));
        byReason.forEach(
                (reason, count) ->
                        accounting
                                .append("reason ")
                                .append(reason)
                                .append(' ')
                                .append(count)
                                .append('\n'));
        return accounting.toString();
    }

    private static String diff(String expected, String actual) {
        if (expected.equals(actual)) {
            return "";
        }
        List<String> expectedLines = List.of(expected.split("\n", -1));
        List<String> actualLines = List.of(actual.split("\n", -1));
        StringBuilder diff = new StringBuilder();
        for (int index = 0; index < Math.max(expectedLines.size(), actualLines.size()); index++) {
            String expectedLine = index < expectedLines.size() ? expectedLines.get(index) : "";
            String actualLine = index < actualLines.size() ? actualLines.get(index) : "";
            if (expectedLine.equals(actualLine)) {
                continue;
            }
            if (!expectedLine.isEmpty()) {
                diff.append("-").append(Diagnostics.escape(expectedLine)).append('\n');
            }
            if (!actualLine.isEmpty()) {
                diff.append("+").append(Diagnostics.escape(actualLine)).append('\n');
            }
        }
        return diff.toString();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ApiInputException("Could not read " + path + ".", exception);
        }
    }

    /**
     * Publishes the accepted manifest as evidence by copying its bytes. Re-rendering the parsed model
     * would allocate the whole document a second time to produce the same bytes; the
     * parse-then-render round trip is frozen by the generator's own tests instead.
     */
    private static void copy(Path source, Path target) {
        try {
            Path parent = target.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new ApiInputException(
                    "Could not copy " + source + " to " + target + ".", exception);
        }
    }

    private static void write(Path path, String content) {
        try {
            Path parent = path.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ApiInputException("Could not write " + path + ".", exception);
        }
    }
}
