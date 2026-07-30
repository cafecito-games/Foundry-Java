# Surface-Manifest Size and Verifier Heap — Design

Resolves [#46](https://github.com/cafecito-games/Foundry-Java/issues/46). Depends on #37 (PR #45),
which introduced the versioned binding-neutral surface manifest. Informs #40 (Maven Central release
pipeline).

## Problem

`:foundry-java-runtime:verifyGeneratedRealization` carries `maxHeapSize = "3g"`, imposing a memory
floor on every `./gradlew check`, including on contributors' machines. The stated cause is that the
verifier parses the whole 25 MB surface manifest alongside the whole 12.7 MB realization map. The
issue also reports that the manifest is uploaded whole as continuous-integration evidence on every
run.

## Measurements

All figures were reproduced on this repository's pinned inputs
(`api/current`, engine API `v0.1.0-alpha.14`) with Temurin JDK 17.0.19 on macOS.

### Artifact sizes

| Artifact | Raw bytes | gzip -6 |
| --- | --- | --- |
| `foundry-java-surface-manifest.json` (57,899 entries) | 25,171,662 | 837,959 |
| Neutral portion alone, every `binding_specific` object stripped | 11,681,754 | 461,915 |
| `realization-map.tsv` | 12,684,126 | 702,794 |

26,671 of the 57,899 entries are realized, covering 30,023 realized members in total.

### Heap floor

`RealizationVerifier` run directly against the real artifacts, bisecting `-Xmx`:

| `-Xmx` | Result |
| --- | --- |
| 512m | passes, 4.2 s |
| 384m | passes, 6.1 s |
| 320m | passes, 7.6 s |
| 256m | `OutOfMemoryError` inside `SurfaceManifest$Entry.parse` |

Cumulative live set by stage, measured at `-Xmx3g`:

| Stage | Live set |
| --- | --- |
| Accepted inputs + compatibility manifest | ~180 MB |
| \+ realization map | ~300 MB |
| \+ compiled generated surface | ~210 MB |
| \+ parsed surface manifest | ~480 MB |
| \+ manifest-versus-map comparison | ~460 MB |
| \+ canonical re-render of 25,171,662 chars | ~850 MB |

Live set is sampled without a forced collection, so a stage that drops below its predecessor
reflects a collection between samples rather than memory being released by that stage.

## What the measurements change

Two of the issue's premises do not survive contact with the numbers.

**The upload is not 25 MB of storage.** `actions/upload-artifact` zips its input at compression
level 6, so the manifest's contribution to `foundry-java-check-evidence` is roughly 840 KB, not
25 MB. The real defect in `ci.yml` is that the manifest is uploaded **twice**: once through
`**/build/reports/**` (the verified evidence copy) and again through the explicit
`foundry-java-runtime/build/generated/foundryApi/foundry-java-surface-manifest.json` entry.

**3 GB was never required.** The measured floor is between 256m and 320m — roughly a tenth of the
configured cap. The genuine finding is not that the verifier needs a large heap, but that it does
the same O(n) work three redundant times:

1. `RealizationMap.sha256()` renders the entire 12.7 MB map as a `String` on every call, and is
   called twice per verification — once inside `disagreementsWith` via `from`, once from
   `accounting`.
2. `disagreementsWith` materialises a **second complete manifest** through `from(map, provenance)`,
   then copies its own `entries` into a further `TreeMap`.
3. The verifier re-renders all 25 MB through `canonicalJson()` solely to write a file byte-identical
   to the one it just read.

## Design

No schema change, no weakened gate, no rewritten tests. The existing generator test suite is the
contract: every change below is behaviour-preserving, and the suite must pass unmodified.

### 1. Eager, incremental realization-map digest

`RealizationMap.sha256()` currently calls `render()`, building the whole 12.7 MB rendering to hash
it. Feed each entry's rendering into `MessageDigest` incrementally instead, and compute the digest
eagerly in the private constructor into a `final` field.

`RealizationMap` is already immutable, so eager computation needs no lazy field and raises no
thread-safety question. Every construction pays one digest pass; the generator and the verifier each
construct exactly one map, and test fixtures are tiny. `render()` remains for the TSV evidence.

A new test pins the incremental digest equal to the SHA-256 of `render()`'s UTF-8 bytes, so the
optimisation cannot silently change the digest that the manifest carries and the accounting baseline
freezes.

### 2. Entry-at-a-time manifest-versus-map comparison

Rewrite `SurfaceManifest.disagreementsWith(RealizationMap)` so it never materialises a second
manifest:

- Iterate the map's entries in source-identity order. For each, derive the single expected
  `SurfaceManifest.Entry` and compare it against `entries.get(sourceIdentity)` immediately. Exactly
  one derived entry is alive at a time.
- Report manifest-side extras in a second pass over `entries`, keeping those for which
  `map.entry(sourceIdentity) == null`.
- Compare `realization_map_format` and `realization_map_sha256` directly against `RealizationMap.FORMAT`
  and `map.sha256()` rather than against a derived manifest's copies of them.

Diagnostics, their `SURFACE_MANIFEST_DISAGREES_WITH_REALIZATION_MAP` prefix, and their ordering are
unchanged. `SurfaceManifestTest` already pins both asymmetric directions
(`disagreementDetectsAMissingEntry`, `disagreementDetectsAnEntryTheMapDoesNotCover`) plus tampered
reasons, availability, binding-specific members, and map digest.

### 3. Copy the evidence manifest instead of re-rendering it

In `RealizationVerifier.main`, replace

```java
write(reportDirectory.resolve(SURFACE_MANIFEST_FILE_NAME), surfaceManifest.canonicalJson());
```

with a `Files.copy` of the input manifest to the same destination, using `REPLACE_EXISTING` so
incremental reruns succeed, and creating the report directory first as `write` already does.

This removes the largest single transient in the verification. Nothing goes unverified as a result:
`SurfaceManifest.parse` still runs on the file and still fails closed on every schema violation, and
the parse-then-render round-trip that the re-render implicitly exercised is already pinned by
`SurfaceManifestTest.parseRoundTripsTheCanonicalRendering`.

The one behavioural difference is that the evidence copy is now the producer's bytes rather than a
re-render of the parsed model. The producer's canonical output is proven separately by
`canonicalJsonFreezesTheNeutralSchemaAndNamespacesEveryBindingSpecificField` and
`renderingIsDeterministicAndIndependentOfEntryOrder`, and by the manifest's stable digest.

### 4. Heap budget as a ratchet

In `foundry-java-runtime/build.gradle.kts`, replace `maxHeapSize = "3g"` with `"512m"` and replace
the comment with the measured basis: the verification exhausts 256m inside manifest parsing, passes
at 320m against the current pinned inputs, and 512m is the reviewed budget with roughly 1.6×
headroom.

An explicit cap is preferred over deleting the line. The JVM default is a quarter of physical RAM,
so the effective budget would otherwise vary from about 1 GB in a small container to 9 GB on a 36 GB
workstation, and a change that multiplied the verifier's allocation would pass quietly on large
machines while failing for contributors on small ones. Holding the number explicit makes memory a
gated budget: raising it is a reviewable edit that must carry fresh measurement, exactly as the
frozen accounting baseline requires justification to move.

`generateFoundryApi` keeps the JVM default. It is out of scope for this issue; it was measured to
pass at 384m.

### 5. Upload the manifest once

Remove the explicit
`foundry-java-runtime/build/generated/foundryApi/foundry-java-surface-manifest.json` line from the
`foundry-java-check-evidence` upload in `.github/workflows/ci.yml`. The verified copy the verifier
writes into `foundry-java-runtime/build/reports/foundry-realization/` is already collected, and it
is the copy worth keeping because it is the one the gate accepted.

The `foundry-java-runtime/build/reports/foundry-realization/**` line is itself subsumed by
`**/build/reports/**`. That redundancy is left alone; it is harmless and outside this issue.

### 6. Record the size decision so #40 inherits it

Add a *Size and memory* section to `docs/binding-neutral-surface-manifest.md` carrying:

- the artifact sizes and compression ratios measured above;
- that `actions/upload-artifact` zips at level 6, so the manifest's stored evidence cost is roughly
  840 KB, and that it is uploaded exactly once;
- the neutral-projection measurement, and the decision **not** to split the document;
- the verifier's heap budget and how to re-measure the floor.

The decision on splitting is settled here so that #40 does not have to re-derive it. A neutral-only
projection is 11,681,754 bytes against 25,171,662 — a factor of 2.3, or 462 KB against 838 KB
compressed — because the neutral portion is dominated by 57,899 `source_identity` strings that
per-entity totality requires. Splitting would cost a `schema_version` bump, a second published
artifact, and a relaxation of `SurfaceManifest.parse` (which currently requires each
`binding_specific` object) to buy under 400 KB. **Foundry-Java publishes the single document,
compressed.**

## Deferral trigger

The 256m exhaustion occurs inside `SurfaceManifest$Entry.parse`, which this design does not change,
so the floor remains in the 256–320 MB band afterwards. Streaming the manifest — a pull reader in
`foundry-java-api-model`, with the existing DOM `JsonParser` rebuilt on top of it so one grammar
exists, and a sorted merge-join against the map — is the correct fix for the parse term, but it is
not justified while 512m holds with headroom.

The trigger is explicit and belongs in the documentation and a follow-up issue: **when the measured
floor exceeds roughly 350 MB, implement the streaming reader rather than raising the cap.** The
ratchet in §4 is what will surface that moment.

## Verification

- `./gradlew check` green with `maxHeapSize = "512m"`.
- The surface-manifest digest is unchanged at
  `ea25a772deab4abfdc5b165fdf3066b3c34584527fe1fc41284a2e0a22fc0961`, proving no behavioural change.
- The evidence copy under `build/reports/foundry-realization/` is `cmp`-identical to the generated
  manifest.
- `foundry-java-runtime/api/foundry-java-realization-accounting.txt` unchanged.
- The generator test suite passes **unmodified**, plus the new realization-map digest-equality test.
- The `-Xmx` bisect is re-run after the change and still passes at 320m or below, so the 512m cap
  retains at least 1.6× headroom. The resulting figure is what the documentation records.

## Out of scope

- The streaming JSON reader and merge-join verification (see *Deferral trigger*).
- Enforcing entry ordering as a schema constraint.
- A heap cap on `generateFoundryApi`.
- Any `schema_version` bump, document split, or additional published artifact.
