# Surface-Manifest Size and Verifier Heap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the redundant O(n) work in realization verification, replace the unjustified 3 GB
heap cap with a measured 512m budget that acts as a regression ratchet, upload the surface manifest
once instead of twice, and record the size measurements that settle the document-split question for
#40.

**Architecture:** Three behaviour-preserving refactors in the generator — an eagerly computed
incremental realization-map digest, an entry-at-a-time manifest-versus-map comparison that never
materialises a second manifest, and a byte copy in place of a 25 MB re-render — followed by the
Gradle, CI, and documentation changes they unlock. The existing generator test suite is the
contract: it must pass unmodified, and the surface-manifest digest must not move.

**Tech Stack:** Java 17, Gradle (Kotlin DSL), JUnit 5, GitHub Actions.

**Spec:** [`docs/superpowers/specs/2026-07-29-surface-manifest-size-and-verifier-heap-design.md`](../specs/2026-07-29-surface-manifest-size-and-verifier-heap-design.md)

## Environment note

This repository has no `local.properties`, and `:foundry-java-android` fails at configuration time
without an Android SDK location. **Every** Gradle command in this plan must therefore be run with the
SDK exported:

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
```

## Reference measurements

Recorded before any change, on `api/current` (engine API `v0.1.0-alpha.14`) with Temurin JDK
17.0.19. Later tasks compare against these.

| Fact | Value |
| --- | --- |
| `foundry-java-surface-manifest.json` | 25,171,662 bytes, 57,899 entries |
| Its digest | `ea25a772deab4abfdc5b165fdf3066b3c34584527fe1fc41284a2e0a22fc0961` |
| `realization-map.tsv` | 12,684,126 bytes |
| Verifier heap floor | OOM at 256m, passes at 320m |

## File Structure

| File | Responsibility | Change |
| --- | --- | --- |
| `foundry-java-generator/src/main/java/games/cafecito/foundry/generator/RealizationMap.java` | Total source-identity → realized-surface map, its rendering, and its digest | Digest computed incrementally and eagerly, stored in a field |
| `foundry-java-generator/src/test/java/games/cafecito/foundry/generator/RealizationMapTest.java` | Freezes the map grammar, ordering, and invariants | One new characterization test pinning the digest to `render()`'s bytes |
| `foundry-java-generator/src/main/java/games/cafecito/foundry/generator/SurfaceManifest.java` | The neutral manifest, its schema, and its agreement with the map | `disagreementsWith` compares one derived entry at a time; derivation extracted and shared with `from` |
| `foundry-java-generator/src/main/java/games/cafecito/foundry/generator/RealizationVerifier.java` | Runs the parity oracle and writes evidence | Publishes the manifest evidence copy by copying bytes |
| `foundry-java-runtime/build.gradle.kts` | Wires generation and verification into the build | `maxHeapSize` becomes a measured 512m with its basis stated |
| `.github/workflows/ci.yml` | Continuous-integration gate and evidence upload | Uploads the manifest once |
| `docs/binding-neutral-surface-manifest.md` | The manifest's public contract | New *Size and memory* section carrying the measurements, the no-split decision, and the streaming trigger |

No file is created; no file is split. Each task produces one commit.

---

### Task 1: Eager, incremental realization-map digest

**Goal:** `RealizationMap.sha256()` returns a digest computed once by streaming entry renderings
into `MessageDigest`, instead of rebuilding the whole 12.7 MB rendering on every call.

**Files:**
- Modify: `foundry-java-generator/src/main/java/games/cafecito/foundry/generator/RealizationMap.java:30-33,72-82`
- Test: `foundry-java-generator/src/test/java/games/cafecito/foundry/generator/RealizationMapTest.java`

**Acceptance Criteria:**
- [ ] `sha256()` returns a field, not a freshly computed value, and no longer calls `render()`
- [ ] The digest is computed in the private constructor, so the class stays immutable with no lazy field
- [ ] A characterization test pins `map.sha256()` equal to the SHA-256 of `map.render()`'s UTF-8 bytes
- [ ] `render()` is unchanged and still used for the TSV evidence
- [ ] Every existing test in `RealizationMapTest` passes unmodified

**Verify:** `./gradlew :foundry-java-generator:test --tests 'games.cafecito.foundry.generator.RealizationMapTest'` → `BUILD SUCCESSFUL`

**Steps:**

- [ ] **Step 1: Write the characterization test**

This test must pass *before* the refactor as well as after — that is the point. It captures the
property the refactor must not break. Append to `RealizationMapTest`:

```java
    @Test
    void theDigestMatchesTheDigestOfTheWholeRendering() throws Exception {
        RealizationMap map =
                RealizationMap.of(
                        List.of(
                                RealizationMap.Entry.realized(
                                        "classes/ExampleNode",
                                        CompatibilityManifest.Status.SUPPORTED,
                                        REASON,
                                        List.of(
                                                JavaMember.ofMethod(
                                                        OWNER, "setValue", List.of("long"), "void"),
                                                JavaMember.ofType(OWNER))),
                                RealizationMap.Entry.notRealized(
                                        "classes/ExampleNode/methods/get_value/arguments/index",
                                        CompatibilityManifest.Status.SUPPORTED,
                                        REASON,
                                        NonRealizationReason.ARGUMENT_REALIZED_IN_MEMBER_SIGNATURE
                                                .name())));

        assertEquals(
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(map.render().getBytes(StandardCharsets.UTF_8))),
                map.sha256());
    }
```

Add these imports to `RealizationMapTest`:

```java
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
```

- [ ] **Step 2: Run the test against the unchanged implementation**

Run: `./gradlew :foundry-java-generator:test --tests 'games.cafecito.foundry.generator.RealizationMapTest'`
Expected: PASS. It must pass now — it describes today's behaviour. If it fails, stop: the
understanding behind this plan is wrong and the refactor must not proceed.

- [ ] **Step 3: Compute the digest eagerly and incrementally**

In `RealizationMap.java`, replace the field and constructor:

```java
    private final Map<String, Entry> entries;

    private RealizationMap(Map<String, Entry> entries) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(entries)));
    }
```

with:

```java
    private final Map<String, Entry> entries;
    private final String sha256;

    private RealizationMap(Map<String, Entry> entries) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(entries)));
        this.sha256 = digest(this.entries.values());
    }
```

Replace the `sha256()` method:

```java
    /** Returns the SHA-256 of {@link #render()}. */
    public String sha256() {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(render().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Every Java runtime provides SHA-256.", exception);
        }
    }
```

with:

```java
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
```

Add the one new import to `RealizationMap.java`:

```java
import java.util.Collection;
```

`MessageDigest`, `NoSuchAlgorithmException`, `HexFormat`, and `StandardCharsets` are already
imported; leave them.

- [ ] **Step 4: Run the test again**

Run: `./gradlew :foundry-java-generator:test --tests 'games.cafecito.foundry.generator.RealizationMapTest'`
Expected: PASS, including the new test. The digest is unchanged because the bytes fed to
`MessageDigest` are the same bytes `render()` concatenates.

- [ ] **Step 5: Run the whole generator suite**

Run: `./gradlew :foundry-java-generator:test`
Expected: `BUILD SUCCESSFUL`. `SurfaceManifestTest` and `FoundrySourceGeneratorTest` both assert on
map digests, so they cover this change too.

- [ ] **Step 6: Commit**

```bash
git add foundry-java-generator/src/main/java/games/cafecito/foundry/generator/RealizationMap.java \
        foundry-java-generator/src/test/java/games/cafecito/foundry/generator/RealizationMapTest.java
git commit -m "perf(generator): digest the realization map without rendering it"
```

---

### Task 2: Entry-at-a-time manifest-versus-map comparison

**Goal:** `SurfaceManifest.disagreementsWith` stops materialising a second complete manifest,
deriving and discarding one expected entry at a time instead, with identical diagnostics and
ordering.

**Files:**
- Modify: `foundry-java-generator/src/main/java/games/cafecito/foundry/generator/SurfaceManifest.java:117-140,266-314`

**Acceptance Criteria:**
- [ ] `disagreementsWith` no longer calls `from`, and holds at most one derived entry at a time
- [ ] `disagreementsWith` no longer copies `entries` into a second `TreeMap`
- [ ] Entry derivation lives in one private helper used by both `from` and `disagreementsWith`
- [ ] Diagnostic text, the `SURFACE_MANIFEST_DISAGREES_WITH_REALIZATION_MAP` prefix, and message
      ordering are byte-identical to before
- [ ] Every existing test in `SurfaceManifestTest` and `NeutralSurfaceManifestConsumerTest` passes
      **unmodified**

**Verify:** `./gradlew :foundry-java-generator:test` → `BUILD SUCCESSFUL`

**Steps:**

- [ ] **Step 1: Confirm the existing tests are the contract**

Run: `./gradlew :foundry-java-generator:test --tests 'games.cafecito.foundry.generator.SurfaceManifestTest'`
Expected: PASS. These eight disagreement tests are what makes the refactor safe — they pin a tampered
reason, a tampered availability, a tampered binding-specific member, a tampered map digest, an entry
the map does not cover, and a missing entry. Do not modify them in this task.

- [ ] **Step 2: Extract entry derivation and reuse it in `from`**

In `SurfaceManifest.java`, replace the body of `from`:

```java
    /** Derives the manifest from a realization map. This is the only way to produce one. */
    public static SurfaceManifest from(RealizationMap map, Provenance provenance) {
        if (provenance == null) {
            throw new ApiInputException("Surface manifest requires accepted provenance.");
        }
        Map<String, Entry> derived = new TreeMap<>();
        for (RealizationMap.Entry entry : map.entries()) {
            NonRealizationReason bindingReason =
                    entry.isRealized()
                            ? null
                            : NonRealizationReason.require(entry.nonRealizationReason());
            derived.put(
                    entry.sourceIdentity(),
                    new Entry(
                            entry.sourceIdentity(),
                            entry.status(),
                            entry.isRealized(),
                            entry.realizedMembers(),
                            bindingReason,
                            entry.reasonCode()));
        }
        return new SurfaceManifest(
                SCHEMA_VERSION, provenance, RealizationMap.FORMAT, map.sha256(), derived);
    }
```

with:

```java
    /** Derives the manifest from a realization map. This is the only way to produce one. */
    public static SurfaceManifest from(RealizationMap map, Provenance provenance) {
        if (provenance == null) {
            throw new ApiInputException("Surface manifest requires accepted provenance.");
        }
        Map<String, Entry> derived = new TreeMap<>();
        for (RealizationMap.Entry entry : map.entries()) {
            derived.put(entry.sourceIdentity(), derive(entry));
        }
        return new SurfaceManifest(
                SCHEMA_VERSION, provenance, RealizationMap.FORMAT, map.sha256(), derived);
    }

    /** Restates one map entry in neutral vocabulary, with its Java detail namespaced. */
    private static Entry derive(RealizationMap.Entry entry) {
        NonRealizationReason bindingReason =
                entry.isRealized()
                        ? null
                        : NonRealizationReason.require(entry.nonRealizationReason());
        return new Entry(
                entry.sourceIdentity(),
                entry.status(),
                entry.isRealized(),
                entry.realizedMembers(),
                bindingReason,
                entry.reasonCode());
    }
```

- [ ] **Step 3: Compare entry by entry**

Replace `disagreementsWith` in its entirety:

```java
    /**
     * Re-derives the manifest from {@code map} and reports every difference, ordered by source
     * identity. An empty result proves the manifest is the map, restated in neutral vocabulary.
     */
    public List<String> disagreementsWith(RealizationMap map) {
        SurfaceManifest expected = from(map, provenance);
        List<String> disagreements = new ArrayList<>();
        if (!expected.realizationMapSha256.equals(realizationMapSha256)) {
            disagreements.add(
                    DISAGREEMENT
                            + " field=realization_map_sha256 expected="
                            + Diagnostics.escape(expected.realizationMapSha256)
                            + " observed="
                            + Diagnostics.escape(realizationMapSha256));
        }
        if (!expected.realizationMapFormat.equals(realizationMapFormat)) {
            disagreements.add(
                    DISAGREEMENT
                            + " field=realization_map_format expected="
                            + Diagnostics.escape(expected.realizationMapFormat)
                            + " observed="
                            + Diagnostics.escape(realizationMapFormat));
        }
        Map<String, Entry> observedEntries = new TreeMap<>(entries);
        for (Entry expectedEntry : expected.entries.values()) {
            Entry observed = observedEntries.remove(expectedEntry.sourceIdentity());
            if (observed == null) {
                disagreements.add(
                        disagreement(
                                expectedEntry.sourceIdentity(), expectedEntry.render(), "absent"));
            } else if (!observed.equals(expectedEntry)) {
                disagreements.add(
                        disagreement(
                                expectedEntry.sourceIdentity(),
                                expectedEntry.render(),
                                observed.render()));
            }
        }
        observedEntries
                .values()
                .forEach(
                        observed ->
                                disagreements.add(
                                        disagreement(
                                                observed.sourceIdentity(),
                                                "absent",
                                                observed.render())));
        return List.copyOf(disagreements);
    }
```

with:

```java
    /**
     * Re-derives the manifest from {@code map} and reports every difference, ordered by source
     * identity. An empty result proves the manifest is the map, restated in neutral vocabulary.
     *
     * <p>Derivation happens one entry at a time. Deriving a whole second manifest to compare against
     * would double the resident cost of a document that covers every engine-API entity.
     */
    public List<String> disagreementsWith(RealizationMap map) {
        List<String> disagreements = new ArrayList<>();
        String expectedMapSha256 = map.sha256();
        if (!expectedMapSha256.equals(realizationMapSha256)) {
            disagreements.add(
                    DISAGREEMENT
                            + " field=realization_map_sha256 expected="
                            + Diagnostics.escape(expectedMapSha256)
                            + " observed="
                            + Diagnostics.escape(realizationMapSha256));
        }
        if (!RealizationMap.FORMAT.equals(realizationMapFormat)) {
            disagreements.add(
                    DISAGREEMENT
                            + " field=realization_map_format expected="
                            + Diagnostics.escape(RealizationMap.FORMAT)
                            + " observed="
                            + Diagnostics.escape(realizationMapFormat));
        }
        int covered = 0;
        for (RealizationMap.Entry mapEntry : map.entries()) {
            Entry expectedEntry = derive(mapEntry);
            Entry observed = entries.get(expectedEntry.sourceIdentity());
            if (observed == null) {
                disagreements.add(
                        disagreement(
                                expectedEntry.sourceIdentity(), expectedEntry.render(), "absent"));
                continue;
            }
            covered++;
            if (!observed.equals(expectedEntry)) {
                disagreements.add(
                        disagreement(
                                expectedEntry.sourceIdentity(),
                                expectedEntry.render(),
                                observed.render()));
            }
        }
        if (covered != entries.size()) {
            for (Entry observed : entries.values()) {
                if (map.entry(observed.sourceIdentity()) == null) {
                    disagreements.add(
                            disagreement(observed.sourceIdentity(), "absent", observed.render()));
                }
            }
        }
        return List.copyOf(disagreements);
    }
```

Why this preserves ordering: `map.entries()` is sorted by source identity, and so was
`expected.entries.values()`; `entries.values()` is sorted, and so was the leftover `TreeMap`. Why it
preserves meaning: the derived manifest covered exactly the map's identities, so
`map.entry(identity) == null` selects the same leftovers the removal loop did. The
`realization_map_format` comparison targets `RealizationMap.FORMAT` directly, which is what a derived
manifest carried.

- [ ] **Step 4: Run the manifest tests**

Run: `./gradlew :foundry-java-generator:test --tests 'games.cafecito.foundry.generator.SurfaceManifestTest'`
Expected: PASS, with no test file edited. If a diagnostic-text assertion fails, the refactor changed
observable behaviour — fix the implementation, not the test.

- [ ] **Step 5: Run the whole generator suite**

Run: `./gradlew :foundry-java-generator:test`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add foundry-java-generator/src/main/java/games/cafecito/foundry/generator/SurfaceManifest.java
git commit -m "perf(generator): compare the surface manifest to the map one entry at a time"
```

---

### Task 3: Publish the manifest evidence copy by copying bytes

**Goal:** `RealizationVerifier` writes the evidence copy of the surface manifest with `Files.copy`
instead of re-rendering 25 MB of canonical JSON to reproduce the bytes it just read.

**Files:**
- Modify: `foundry-java-generator/src/main/java/games/cafecito/foundry/generator/RealizationVerifier.java:132,253-271`

**Acceptance Criteria:**
- [ ] `RealizationVerifier.main` no longer calls `surfaceManifest.canonicalJson()`
- [ ] The evidence copy under `build/reports/foundry-realization/` is byte-identical to the generated
      manifest
- [ ] The copy still happens before any failure is raised, so evidence exists on failure as well as
      success
- [ ] `SurfaceManifest.parse` still runs on the manifest, so the fail-closed schema checks are intact
- [ ] Every existing test passes unmodified

**Verify:** `./gradlew :foundry-java-runtime:verifyGeneratedRealization` then
`cmp foundry-java-runtime/build/generated/foundryApi/foundry-java-surface-manifest.json foundry-java-runtime/build/reports/foundry-realization/foundry-java-surface-manifest.json`
→ `BUILD SUCCESSFUL` and no `cmp` output

**Steps:**

- [ ] **Step 1: Replace the re-render with a copy**

In `RealizationVerifier.main`, replace this line:

```java
        write(reportDirectory.resolve(SURFACE_MANIFEST_FILE_NAME), surfaceManifest.canonicalJson());
```

with:

```java
        copy(surfaceManifestPath, reportDirectory.resolve(SURFACE_MANIFEST_FILE_NAME));
```

Leave the surrounding `write` calls for `realization-map.tsv`, `realization-accounting.txt`,
`realization-diff.txt`, and `realization-violations.txt` exactly as they are, and leave the line's
position in the sequence unchanged so evidence ordering is untouched.

- [ ] **Step 2: Add the copy helper**

Add beside the existing private `write` method at the bottom of `RealizationVerifier`:

```java
    /**
     * Publishes the accepted manifest as evidence by copying its bytes. Re-rendering the parsed
     * model would allocate the whole document a second time to produce the same bytes; the
     * parse-then-render round trip is frozen by {@code SurfaceManifestTest} instead.
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
```

Add the one new import:

```java
import java.nio.file.StandardCopyOption;
```

`surfaceManifest` is still used for `disagreementsWith` and `provenanceDrift`, so it stays; only the
re-render goes.

- [ ] **Step 3: Verify the evidence copy is byte-identical**

```bash
./gradlew :foundry-java-runtime:verifyGeneratedRealization
cmp foundry-java-runtime/build/generated/foundryApi/foundry-java-surface-manifest.json \
    foundry-java-runtime/build/reports/foundry-realization/foundry-java-surface-manifest.json \
  && echo IDENTICAL
```

Expected: `BUILD SUCCESSFUL` then `IDENTICAL`.

- [ ] **Step 4: Verify the manifest digest has not moved**

```bash
shasum -a 256 foundry-java-runtime/build/reports/foundry-realization/foundry-java-surface-manifest.json
```

Expected: `ea25a772deab4abfdc5b165fdf3066b3c34584527fe1fc41284a2e0a22fc0961`. A different digest means
Task 1, 2, or 3 changed behaviour; stop and find out which.

- [ ] **Step 5: Run the whole generator suite**

Run: `./gradlew :foundry-java-generator:test`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add foundry-java-generator/src/main/java/games/cafecito/foundry/generator/RealizationVerifier.java
git commit -m "perf(generator): copy the surface manifest evidence instead of re-rendering it"
```

---

### Task 4: Replace the 3 GB cap with a measured 512m budget

**Goal:** `verifyGeneratedRealization` declares a heap budget backed by measurement, low enough to
stop imposing a 3 GB floor on contributors and explicit enough to fail the build if a future change
multiplies the verifier's allocation.

**Files:**
- Modify: `foundry-java-runtime/build.gradle.kts:113-115`

**Acceptance Criteria:**
- [ ] `maxHeapSize = "512m"`, with a comment stating the measured basis rather than a narrative
- [ ] The `-Xmx` bisect, re-run after Tasks 1–3, still passes at 320m or below, so 512m keeps at
      least 1.6× headroom
- [ ] The measured post-change floor is written down for Task 6 to record
- [ ] `./gradlew check` is green at 512m
- [ ] `generateFoundryApi` is left alone

**Verify:** `./gradlew check` → `BUILD SUCCESSFUL`

**Steps:**

- [ ] **Step 1: Re-measure the floor after Tasks 1–3**

The verifier's `main` takes seven arguments, so it can be bisected directly without Gradle. Run this
from the repository root after a successful `:foundry-java-runtime:verifyGeneratedRealization`:

```bash
CP="foundry-java-generator/build/libs/foundry-java-generator-0.1.0-SNAPSHOT.jar"
CP="$CP:foundry-java-api-model/build/libs/foundry-java-api-model-0.1.0-SNAPSHOT.jar"
CP="$CP:foundry-java-annotations/build/libs/foundry-java-annotations-0.1.0-SNAPSHOT.jar"
G="foundry-java-runtime/build/generated/foundryApi"
for xmx in 512m 384m 320m 288m 256m; do
  printf '=== -Xmx%s ===\n' "$xmx"
  java -Xmx$xmx -cp "$CP" games.cafecito.foundry.generator.RealizationVerifier \
    api/current \
    "$G/realization-map.tsv" \
    foundry-java-runtime/build/classes/java/main \
    foundry-java-runtime/api/foundry-java-realization-accounting.txt \
    "foundry-java-runtime/build/tmp/heap-bisect-$xmx" \
    "$G/foundry-java-surface-manifest.json" \
    0.1.0-SNAPSHOT \
    && echo PASS || echo FAIL
done
rm -rf foundry-java-runtime/build/tmp/heap-bisect-*
```

Expected: PASS at 512m, 384m, and 320m. Record the lowest passing value — Task 6 writes it into the
documentation. If 320m now FAILs, the refactors regressed memory; investigate before continuing. If
288m or 256m now PASSes, that is the improvement Tasks 1–3 bought; record it, but still set the cap
to 512m as designed.

- [ ] **Step 2: Set the measured budget**

In `foundry-java-runtime/build.gradle.kts`, replace:

```kotlin
        // Parsing the whole engine-API surface manifest alongside the realization map needs more
        // than the forked default heap on a stock continuous-integration runner.
        maxHeapSize = "3g"
```

with:

```kotlin
        // A measured budget, not a ceiling picked for safety: verification exhausts 256m inside
        // surface-manifest parsing and passes at 320m against the pinned engine API. Holding it
        // explicit keeps the requirement identical on every machine, because the JVM default is a
        // quarter of physical RAM. Raising it requires a fresh measurement — see
        // docs/binding-neutral-surface-manifest.md.
        maxHeapSize = "512m"
```

- [ ] **Step 3: Run the full gate**

Run: `./gradlew check`
Expected: `BUILD SUCCESSFUL`. This is the first run that exercises the new cap through Gradle's fork.

- [ ] **Step 4: Confirm the frozen baseline did not move**

```bash
git status --porcelain -- foundry-java-runtime/api/foundry-java-realization-accounting.txt
```

Expected: no output. The per-entity accounting is frozen evidence; none of this work may change it.

- [ ] **Step 5: Commit**

```bash
git add foundry-java-runtime/build.gradle.kts
git commit -m "build(runtime): budget realization verification at a measured 512m"
```

---

### Task 5: Upload the surface manifest once

**Goal:** The `foundry-java-check-evidence` artifact carries the verified surface manifest once
instead of carrying both the generated and the verified copy.

**Files:**
- Modify: `.github/workflows/ci.yml:66`

**Acceptance Criteria:**
- [ ] The explicit `build/generated/foundryApi/foundry-java-surface-manifest.json` path is gone from
      the upload
- [ ] The verified copy still reaches the artifact through the reports paths
- [ ] No other upload path, `if: always()`, or `if-no-files-found` setting changes

**Verify:** `git diff --stat .github/workflows/ci.yml` → one file changed, one deletion

**Steps:**

- [ ] **Step 1: Delete the duplicate path**

In the `Upload build and native verification evidence` step of `.github/workflows/ci.yml`, delete
this single line from the `path:` block:

```yaml
            foundry-java-runtime/build/generated/foundryApi/foundry-java-surface-manifest.json
```

Leave `**/build/reports/**` and
`foundry-java-runtime/build/reports/foundry-realization/**` in place. The verifier writes the
accepted copy into the second of those, and that is the copy worth keeping because it is the one the
gate accepted. The redundancy between those two lines is pre-existing, harmless, and out of scope.

- [ ] **Step 2: Confirm the evidence path still exists locally**

```bash
ls -l foundry-java-runtime/build/reports/foundry-realization/foundry-java-surface-manifest.json
```

Expected: the file is listed, at 25,171,662 bytes. `upload-artifact` zips at level 6, so its stored
contribution is roughly 840 KB.

- [ ] **Step 3: Confirm the diff is exactly one deletion**

Run: `git diff --stat .github/workflows/ci.yml`
Expected: `1 file changed, 1 deletion(-)`

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: upload the surface manifest once"
```

---

### Task 6: Record the size and memory decisions

**Goal:** `docs/binding-neutral-surface-manifest.md` carries the measurements, the decision not to
split the document, the verifier's heap budget, and the condition under which streaming becomes the
right fix — so #40 inherits the answer instead of re-deriving it.

**Files:**
- Modify: `docs/binding-neutral-surface-manifest.md` (new section after *Schema*, before
  *Compatibility rule for `schema_version`*)

**Acceptance Criteria:**
- [ ] The section states the raw and compressed sizes of the manifest, its neutral portion, and the
      realization map
- [ ] It states that `actions/upload-artifact` compresses, and that the manifest is uploaded once
- [ ] It states the no-split decision with its measured rationale and names #40 as the consumer of
      that decision
- [ ] It states the verifier's heap budget, the measured floor from Task 4 Step 1, and how to
      re-measure it
- [ ] It states the streaming trigger: a measured floor above roughly 350 MB is fixed by streaming,
      not by raising the cap
- [ ] No claim in the section is unmeasured

**Verify:** `grep -c 'Size and memory' docs/binding-neutral-surface-manifest.md` → `2` (the heading
plus the cross-reference added in Step 2)

**Steps:**

- [ ] **Step 1: Insert the section**

Insert this between the end of the *Schema* section (after the `binding_specific` discussion, which
ends with the fixture-consumer paragraph) and the `## Compatibility rule for schema_version` heading.
Replace `<MEASURED-FLOOR>` with the lowest passing `-Xmx` recorded in Task 4 Step 1 — leaving the
placeholder in is a documentation failure:

```markdown
## Size and memory

The manifest covers every accepted engine-API entity, so its size tracks the engine API rather than
the binding. Measured against `api/current` (engine API `v0.1.0-alpha.14`, 57,899 entries, 26,671 of
them realized across 30,023 realized members):

| Artifact | Raw bytes | gzip -6 |
| --- | --- | --- |
| `foundry-java-surface-manifest.json` | 25,171,662 | 837,959 |
| Its neutral portion alone | 11,681,754 | 461,915 |
| `realization-map.tsv` | 12,684,126 | 702,794 |

`actions/upload-artifact` compresses its input at level 6, so the manifest contributes roughly 840 KB
to `foundry-java-check-evidence`, not 25 MB. It is uploaded once, as the verified copy in the
realization report directory.

### The document is not split

A neutral-only projection — every `binding_specific` object omitted — is 11,681,754 bytes against
25,171,662, a factor of 2.3, because the neutral portion is dominated by the 57,899 `source_identity`
strings that per-entity totality requires. Splitting the document into neutral and binding-specific
halves would cost a `schema_version` bump, a second published artifact, and a relaxation of
`SurfaceManifest.parse`, which requires each `binding_specific` object, to buy under 400 KB
compressed. Foundry-Java therefore publishes the single document, compressed. The release pipeline
inherits this decision rather than revisiting it.

### Verification heap budget

`:foundry-java-runtime:verifyGeneratedRealization` declares `maxHeapSize = "512m"`. That is a
measured budget: verification exhausts 256m inside surface-manifest parsing and passes at
<MEASURED-FLOOR> against the pinned inputs on JDK 17. The cap is held explicit because the JVM
default is a quarter of physical RAM, which would otherwise make the requirement vary from about
1 GB in a small container to several gigabytes on a workstation, hiding a memory regression from
everyone but the contributors least able to absorb it.

Re-measure before changing it, by running `games.cafecito.foundry.generator.RealizationVerifier`
directly against the generated artifacts under decreasing `-Xmx` values. Verification loads the
accepted inputs, the compatibility manifest, the realization map, the compiled generated surface, and
the manifest, so its floor grows with the engine API.

**When the measured floor exceeds roughly 350 MB, stream the manifest rather than raise the cap.**
The exhaustion point is inside per-entry parsing, so the fix is a pull reader in
`foundry-java-api-model` — with the existing DOM `JsonParser` rebuilt on top of it, so one JSON
grammar exists — and a sorted merge-join against the realization map. That work is deliberately not
done while 512m holds with headroom.
```

- [ ] **Step 2: Update the location table note**

In the *Location* section, the sentence describing the verified copy already says continuous
integration uploads it as part of `foundry-java-check-evidence`. Append one clause so the single-copy
decision is stated where a reader looks for it:

Replace:

```markdown
integration uploads as part of `foundry-java-check-evidence` on success and on failure alike.
```

with:

```markdown
integration uploads as part of `foundry-java-check-evidence` on success and on failure alike. That
verified copy is the only one uploaded; see [Size and memory](#size-and-memory).
```

- [ ] **Step 3: Confirm no placeholder survived**

```bash
grep -n 'MEASURED-FLOOR\|TBD\|TODO' docs/binding-neutral-surface-manifest.md
```

Expected: no output.

- [ ] **Step 4: Confirm the section is present once**

Run: `grep -c 'Size and memory' docs/binding-neutral-surface-manifest.md`
Expected: `2` — the heading plus the cross-reference added in Step 2.

- [ ] **Step 5: Commit**

```bash
git add docs/binding-neutral-surface-manifest.md
git commit -m "docs(manifest): record the manifest's size, heap budget, and no-split decision"
```

---

### Task 7: Verify the whole gate and file the streaming follow-up

**Goal:** The full gate is green with every change in place, and the deferred streaming work is
recorded as a tracked follow-up with its trigger condition.

**Files:**
- No source changes. Creates a GitHub issue.

**Acceptance Criteria:**
- [ ] `./gradlew check` green from a clean build directory
- [ ] The surface-manifest digest is still
      `ea25a772deab4abfdc5b165fdf3066b3c34584527fe1fc41284a2e0a22fc0961`
- [ ] `git status --porcelain` shows no modification to `foundry-java-runtime/api/`
- [ ] A follow-up issue exists for the streaming reader, naming the 350 MB trigger and depending on
      #46

**Verify:** `./gradlew clean check` → `BUILD SUCCESSFUL`

**Steps:**

- [ ] **Step 1: Run the gate from clean**

```bash
./gradlew clean check
```

Expected: `BUILD SUCCESSFUL`. Clean matters here: the cap must hold for a build that regenerates and
re-verifies everything, not only for an incremental one.

- [ ] **Step 2: Re-confirm determinism and the frozen baseline**

```bash
shasum -a 256 foundry-java-runtime/build/generated/foundryApi/foundry-java-surface-manifest.json
git status --porcelain -- foundry-java-runtime/api/
```

Expected: the digest `ea25a772deab4abfdc5b165fdf3066b3c34584527fe1fc41284a2e0a22fc0961`, and no
output from `git status`.

- [ ] **Step 3: File the follow-up — confirm with the repository owner before running**

This creates a public issue, so get a go-ahead first, then run:

```bash
gh issue create --repo cafecito-games/Foundry-Java \
  --title "Stream the surface manifest when the verifier's heap floor exceeds 350 MB" \
  --body "$(cat <<'EOF'
Follow-up to #46. Track-only: nothing is broken and no gate is at risk.

#46 removed the redundant O(n) work in `verifyGeneratedRealization` and replaced the unmeasured 3 GB
cap with a measured `maxHeapSize = "512m"`. The remaining term is per-entry parsing of the surface
manifest into a DOM, which grows with the engine API: verification exhausts 256m inside
`SurfaceManifest$Entry.parse` today.

The trigger recorded in `docs/binding-neutral-surface-manifest.md` is explicit: **when the measured
floor exceeds roughly 350 MB, stream the manifest rather than raise the cap.**

The fix, when that happens:

- a pull reader in `foundry-java-api-model`, with the existing DOM `JsonParser` rebuilt on top of it
  so exactly one JSON grammar exists in the codebase;
- a sorted merge-join of the manifest's `entries` against the realization map, which also turns the
  documented source-identity ordering into a gated constraint rather than an incidental property.

Depends on #46. Does not gate epic closure.
EOF
)"
```

- [ ] **Step 4: Commit nothing, push the branch**

There is nothing to commit in this task. Push the six commits from Tasks 1–6 and open the pull
request referencing #46.

---

## Notes for the implementer

**Why the tests are not rewritten.** Tasks 1–3 are behaviour-preserving refactors of code that is
already gated. The existing suite is the proof. A failing existing test in any of those tasks means
the refactor is wrong — never adjust the test to match new behaviour.

**Why the cap is not simply deleted.** Deleting `maxHeapSize` hands the budget to JVM ergonomics,
which is a quarter of physical RAM. The requirement would then differ on every machine and a memory
regression would reach contributors on small machines first. An explicit number makes the budget a
reviewable fact.

**What is deliberately not in this plan.** The streaming JSON reader and merge-join verification;
enforcing entry ordering as a schema constraint; a heap cap on `generateFoundryApi`; any
`schema_version` bump, document split, or additional published artifact. The spec's *Out of scope*
section is authoritative.
