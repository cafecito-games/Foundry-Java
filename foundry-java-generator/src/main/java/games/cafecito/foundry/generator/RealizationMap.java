package games.cafecito.foundry.generator;

import games.cafecito.foundry.api.model.ApiInputException;
import games.cafecito.foundry.api.model.CompatibilityManifest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Total map from every accepted source identity to the generated Java surface it realizes.
 *
 * <p>Each identity appears exactly once and resolves to one of two states: a non-empty list of
 * realized Java members, or one approved non-realization reason. There is no third state; an
 * identity that resolves to neither cannot be represented.
 *
 * <p>Entries are ordered by source identity, so the rendering is byte-stable across clean,
 * incremental, and multi-module builds.
 */
public final class RealizationMap {
    /** Format marker of the rendered map, bumped whenever the line grammar changes. */
    public static final String FORMAT = "foundry-java-realization-map/1";

    private final Map<String, Entry> entries;
    private final String sha256;

    private RealizationMap(Map<String, Entry> entries) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(entries)));
        this.sha256 = digest(this.entries.values());
    }

    /** Returns a map over the given entries, rejecting duplicate source identities. */
    public static RealizationMap of(List<Entry> entries) {
        Map<String, Entry> byIdentity = new TreeMap<>();
        for (Entry entry : entries) {
            if (byIdentity.put(entry.sourceIdentity(), entry) != null) {
                throw new ApiInputException(
                        "Realization map covers source identity twice: "
                                + Diagnostics.escape(entry.sourceIdentity())
                                + ".");
            }
        }
        return new RealizationMap(byIdentity);
    }

    /** Returns every entry ordered by source identity. */
    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    /** Returns the entry for {@code sourceIdentity}, or {@code null} when it is not covered. */
    public Entry entry(String sourceIdentity) {
        return entries.get(sourceIdentity);
    }

    /** Returns the number of covered source identities. */
    public int size() {
        return entries.size();
    }

    /** Renders the map as deterministic, single-line-per-identity text. */
    public String render() {
        StringBuilder rendered = new StringBuilder(FORMAT).append('\n');
        entries.values().forEach(entry -> rendered.append(entry.render()).append('\n'));
        return rendered.toString();
    }

    /** Returns the SHA-256 of {@link #render()}. */
    public String sha256() {
        return sha256;
    }

    /**
     * Digests exactly the bytes {@link #render()} produces without materializing them, so covering
     * the whole engine API costs one pass rather than one multi-megabyte string per call.
     */
    private static String digest(Collection<Entry> entries) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Every Java runtime provides SHA-256.", exception);
        }
        digest.update((FORMAT + "\n").getBytes(StandardCharsets.UTF_8));
        for (Entry entry : entries) {
            digest.update((entry.render() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Parses the rendering produced by {@link #render()}. */
    public static RealizationMap parse(String text) {
        List<String> lines = List.of(text.split("\n", -1));
        if (lines.isEmpty() || !lines.get(0).equals(FORMAT)) {
            throw new ApiInputException("Realization map must start with " + FORMAT + ".");
        }
        List<Entry> parsed = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isEmpty()) {
                continue;
            }
            parsed.add(Entry.parse(line));
        }
        return of(parsed);
    }

    /** One source identity, its vendored classification, and the Java surface it realizes. */
    public record Entry(
            String sourceIdentity,
            CompatibilityManifest.Status status,
            String reasonCode,
            List<JavaMember> realizedMembers,
            String nonRealizationReason) {
        public Entry {
            if (sourceIdentity == null || sourceIdentity.isBlank()) {
                throw new ApiInputException("Realization map entry requires a source identity.");
            }
            if (status == null) {
                throw new ApiInputException(
                        "Realization map entry requires a compatibility status: "
                                + Diagnostics.escape(sourceIdentity)
                                + ".");
            }
            if (reasonCode == null || reasonCode.isBlank()) {
                throw new ApiInputException(
                        "Realization map entry requires a compatibility reason code: "
                                + Diagnostics.escape(sourceIdentity)
                                + ".");
            }
            realizedMembers = List.copyOf(realizedMembers);
            nonRealizationReason = nonRealizationReason == null ? "" : nonRealizationReason;
            if (realizedMembers.isEmpty() == nonRealizationReason.isEmpty()) {
                throw new ApiInputException(
                        "Realization map entry must realize members or declare exactly one "
                                + "non-realization reason: "
                                + Diagnostics.escape(sourceIdentity)
                                + ".");
            }
        }

        /** Returns a realized entry. */
        public static Entry realized(
                String sourceIdentity,
                CompatibilityManifest.Status status,
                String reasonCode,
                List<JavaMember> members) {
            List<JavaMember> sorted = new ArrayList<>(members);
            Collections.sort(sorted);
            return new Entry(sourceIdentity, status, reasonCode, List.copyOf(sorted), "");
        }

        /** Returns an entry that realizes no member of its own. */
        public static Entry notRealized(
                String sourceIdentity,
                CompatibilityManifest.Status status,
                String reasonCode,
                String nonRealizationReason) {
            return new Entry(sourceIdentity, status, reasonCode, List.of(), nonRealizationReason);
        }

        /** Returns whether this entry realizes at least one Java member. */
        public boolean isRealized() {
            return !realizedMembers.isEmpty();
        }

        /** Renders this entry as one tab-separated line. */
        public String render() {
            StringBuilder members = new StringBuilder();
            for (JavaMember member : realizedMembers) {
                if (!members.isEmpty()) {
                    members.append(';');
                }
                members.append(member.render());
            }
            return sourceIdentity
                    + '\t'
                    + status.jsonValue()
                    + '\t'
                    + reasonCode
                    + '\t'
                    + (isRealized() ? "realized" : "not-realized")
                    + '\t'
                    + (isRealized() ? members.toString() : nonRealizationReason);
        }

        private static CompatibilityManifest.Status status(String jsonValue, String identity) {
            for (CompatibilityManifest.Status candidate : CompatibilityManifest.Status.values()) {
                if (candidate.jsonValue().equals(jsonValue)) {
                    return candidate;
                }
            }
            throw new ApiInputException(
                    "Realization map entry "
                            + Diagnostics.escape(identity)
                            + " has unknown compatibility status "
                            + Diagnostics.escape(jsonValue)
                            + ".");
        }

        /** Parses the rendering produced by {@link #render()}. */
        public static Entry parse(String line) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 5) {
                throw new ApiInputException(
                        "Malformed realization map line: " + Diagnostics.escape(line) + ".");
            }
            CompatibilityManifest.Status status = status(fields[1], fields[0]);
            if (fields[3].equals("not-realized")) {
                return notRealized(fields[0], status, fields[2], fields[4]);
            }
            if (!fields[3].equals("realized")) {
                throw new ApiInputException(
                        "Unknown realization state: " + Diagnostics.escape(line) + ".");
            }
            List<JavaMember> members = new ArrayList<>();
            for (String rendered : fields[4].split(";", -1)) {
                members.add(JavaMember.parse(rendered));
            }
            return realized(fields[0], status, fields[2], members);
        }
    }
}
