# Binding-Neutral Surface Manifest

Foundry-Java publishes a versioned, binding-neutral **surface manifest** describing what this
binding realizes from the pinned engine API. It exists so cross-binding parity can later be verified
in one place, without any binding depending on another. This repository implements the producer side
only: it builds no cross-binding comparison and imposes no requirement on Foundry-Swift,
Foundry-Android, or any other binding.

## Location

| Artifact | Path |
| --- | --- |
| Build artifact | `foundry-java-runtime/build/generated/foundryApi/foundry-java-surface-manifest.json` |
| Verified evidence copy | `foundry-java-runtime/build/reports/foundry-realization/foundry-java-surface-manifest.json` |

`:foundry-java-runtime:generateFoundryApi` emits the build artifact next to the generated sources,
the compatibility manifest, and the realization map.
`:foundry-java-runtime:verifyGeneratedRealization` re-reads it, verifies it against the realization
map, and writes the verified copy into the realization report directory, which continuous
integration uploads as part of `foundry-java-check-evidence` on success and on failure alike. That
verified copy is the only one uploaded; see [Size and memory](#size-and-memory).

The manifest is a build output, not a checked-in baseline: it is a restatement of the realization map
whose per-entity accounting is already frozen in
[`foundry-java-runtime/api/foundry-java-realization-accounting.txt`](../foundry-java-runtime/api/foundry-java-realization-accounting.txt).

## Schema

The document is canonical JSON — object keys sorted, no insignificant whitespace, one trailing
newline — so repeat, clean, incremental, and multi-module builds of the same inputs produce
byte-identical output. It contains no timestamp and no absolute path.

### Neutral portion

| Field | Type | Meaning |
| --- | --- | --- |
| `schema_version` | integer | Version of the neutral schema. Currently `1`. |
| `engine_api_version` | string | Version of the pinned engine API description. |
| `engine_api_sha256` | string | SHA-256 of the accepted `extension_api.json`. |
| `binding_id` | string | Stable binding identity. Always `foundry-java` here. |
| `binding_version` | string | Version of the binding release the manifest describes. |
| `generator_version` | string | Accepted generator contract version. |
| `bridge_contract_version` | string | Accepted bridge contract version. |
| `entries` | array | One entry per source entity, ordered by `source_identity`. |

Each entry carries:

| Field | Type | Meaning |
| --- | --- | --- |
| `source_identity` | string | The engine-API entity identity. Unique across the manifest. |
| `availability` | string | `supported`, `excluded-language`, `excluded-platform`, or `excluded-upstream`. |
| `realization` | string | `realized` or `not-realized`. |
| `realized_member_count` | integer | How many binding members the entity realizes. `0` when not realized. |
| `non_realization_reason` | string | Present only when `realization` is `not-realized`. |

`non_realization_reason` comes from a closed, binding-neutral vocabulary that carries no Java idiom:

| Reason | Meaning |
| --- | --- |
| `SUBSUMED_BY_ENCLOSING_SIGNATURE` | The entity appears inside the signature of the member realized for its parent. |
| `SUBSUMED_BY_ENCLOSING_TYPE_ARGUMENT` | The entity appears as a type parameter of the member realized for its parent. |
| `SERVED_BY_ENGINE_ACCESSOR` | The entity is served by an engine accessor the binding already exposes. |
| `SERVED_BY_LAYOUT_QUERY_API` | The entity is a layout table row served by the binding's size or offset query API. |

`NeutralNonRealizationReason.of` maps the Java vocabulary in `NonRealizationReason` onto this one
through a total switch, so approving a new Java reason without deciding its neutral meaning fails to
compile. Neither vocabulary may be widened to make a gate pass.

### Binding-specific portion

Every Java-specific detail — erased signatures, package names, Java type names, the Java
non-realization vocabulary, the vendored compatibility reason code, and the realization map digest —
lives inside a `binding_specific` object that names the binding defining its content:

```json
"binding_specific": {
  "namespace": "foundry-java",
  "compatibility_reason_code": "WS5_MODEL_AND_GENERATOR_REPRESENTABLE",
  "realized_members": ["games.cafecito.foundry.generated.classes.Node#<type>:games.cafecito.foundry.generated.classes.Node"]
}
```

A `binding_specific` object appears at most once at the top level and at most once per entry. A
consumer may ignore every one of them and still compute realization coverage and diff two bindings;
the repository proves this with a fixture consumer
(`foundry-java-generator/src/test/java/games/cafecito/foundry/generator/NeutralSurfaceManifestConsumer.java`)
that shares no code with the producer, rejects any field outside the neutral sets, rejects
binding-specific content that does not name its namespace, never interprets that content, and still
reports coverage and a two-binding diff. Its tests read both this binding's real manifest and a
hand-written sibling manifest that contains no Java vocabulary at all.

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
measured budget: verification exhausts 256m inside surface-manifest parsing and passes at 288m
against the pinned inputs on JDK 17. The cap is held explicit because the JVM default is a quarter of
physical RAM, which would otherwise make the requirement vary from about 1 GB in a small container to
several gigabytes on a workstation, hiding a memory regression from everyone but the contributors
least able to absorb it.

Re-measure before changing it, by running `games.cafecito.foundry.generator.RealizationVerifier`
directly against the generated artifacts under decreasing `-Xmx` values. Verification loads the
accepted inputs, the compatibility manifest, the realization map, the compiled generated surface, and
the manifest, so its floor grows with the engine API.

**When the measured floor exceeds roughly 350 MB, stream the manifest rather than raise the cap.**
The exhaustion point is inside per-entry parsing, so the fix is a pull reader in
`foundry-java-api-model` — with the existing DOM `JsonParser` rebuilt on top of it, so one JSON
grammar exists — and a sorted merge-join against the realization map. That work is deliberately not
done while 512m holds with headroom.

## Compatibility rule for `schema_version`

`schema_version` describes the neutral portion only.

- It is incremented by an explicit, reviewable change whenever a neutral field is added, removed,
  renamed, retyped, or given a new meaning, or whenever the neutral realization or non-realization
  vocabulary changes.
- Content inside a `binding_specific` object may change **without** a version bump, because no
  neutral consumer reads it. A consumer that reads a binding-specific field is depending on that
  binding's private detail and owns the resulting breakage.
- A consumer that encounters a `schema_version` it does not implement **must refuse the manifest**
  and report the version it expected. It must not guess, must not partially interpret the document,
  and must not silently treat the manifest as empty — an unreadable manifest is not a manifest full
  of unrealized entities. Both the producer's `SurfaceManifest.parse` and the fixture neutral
  consumer implement exactly this rule.

## Intended cross-binding use

A separate consuming repository — proposed independently of this one — collects the manifests
published by each binding of the same `engine_api_sha256` and answers two questions from the neutral
portion alone:

1. **Coverage.** For one binding, how many accepted source entities it realizes, and how the rest
   are accounted for per neutral reason.
2. **Diff.** For two bindings, which source identities one realizes and the other does not, and which
   identities only one of them covers at all.

Neither question requires either binding to understand the other's language, and neither binding
depends on the other. Comparison refuses to proceed when two manifests name different
`engine_api_sha256` values, because bindings pinned to different engine API descriptions cannot be
compared meaningfully.

## Derivation and gating

The manifest is derived from the realization map and is never independently synthesized.
`SurfaceManifest.from` is the only way to construct one, and
`SurfaceManifest.disagreementsWith(RealizationMap)` re-derives the manifest from the map and reports
every difference — a wrong realization state, availability, reason, member list, member count,
covered identity, or map digest — each on a single escaped line prefixed
`SURFACE_MANIFEST_DISAGREES_WITH_REALIZATION_MAP`.
`RealizationVerifier` additionally anchors the manifest provenance to the accepted inputs and reports
each mismatch as `SURFACE_MANIFEST_PROVENANCE_DRIFT`. Any of those messages fails
`:foundry-java-runtime:verifyGeneratedRealization`, and therefore `./gradlew check`, after the
evidence has been written.

`SurfaceManifest.parse` also fails closed on the manifest's own inconsistencies: an unknown field,
an unsupported `schema_version`, a `binding_specific` namespace that is not this binding, a
realization state that contradicts its declared member count, a reason outside either closed
vocabulary, or a neutral reason that is not the neutral meaning of the Java reason beside it.
