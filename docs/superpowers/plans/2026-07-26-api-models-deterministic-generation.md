# API Models, Provenance, and Deterministic Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pin accepted FoundryExtension API inputs and provide strict immutable models, exhaustive compatibility classification, and deterministic Java generation that compiles under Java 17.

**Architecture:** `foundry-java-api-model` owns a small dependency-free JSON reader, a schema-aware immutable API tree, provenance/hash validation, stable source identities, and the compatibility manifest. `foundry-java-generator` consumes only that public model and writes deterministic Java metadata stubs and registration descriptors; golden fixtures and the accepted full API prove category coverage, clean-repeat byte identity, and Java compilation.

**Tech Stack:** Java 17 records and sealed interfaces, JUnit 5, Gradle 8.11.1, the JDK compiler API, SHA-256, and the public FoundryExtension release API only.

---

### Task 1: Pin the accepted API release and lock provenance behavior

**Files:**
- Create: `api/current/extension_api.json`
- Create: `api/current/foundry_extension_interface.h`
- Create: `api/current/provenance.json`
- Create: `foundry-java-api-model/src/main/java/games/cafecito/foundry/api/model/ApiInputException.java`
- Create: `foundry-java-api-model/src/main/java/games/cafecito/foundry/api/model/ApiProvenance.java`
- Create: `foundry-java-api-model/src/main/java/games/cafecito/foundry/api/model/ApiInputs.java`
- Create: `foundry-java-api-model/src/test/java/games/cafecito/foundry/api/model/ApiInputsTest.java`

- [ ] **Step 1: Add failing provenance tests**

Test the wished-for API directly:

```java
ApiInputs accepted = ApiInputs.load(apiDirectory);
assertEquals("3923e920b2fb6db68f82dfdab2bf7b1df125492d", accepted.provenance().foundryCommit());
assertThrows(ApiInputException.class, () -> ApiInputs.load(missingHashDirectory));
assertThrows(ApiInputException.class, () -> ApiInputs.load(mismatchedHashDirectory));
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :foundry-java-api-model:test --tests '*ApiInputsTest'
```

Expected: compilation fails because `ApiInputs` and the immutable provenance model do not exist.

- [ ] **Step 3: Pin the accepted release inputs and implement minimal hash validation**

Copy `extension_api.json` and `foundry_extension_interface.h` byte-for-byte from
`Foundry_v0.1.0-alpha.8_api.zip` and record:

```json
{
  "foundry_commit": "3923e920b2fb6db68f82dfdab2bf7b1df125492d",
  "foundry_version": "0.1.0-alpha.8",
  "api_version": "0.1.0-alpha.8",
  "abi_minimum": "0.1.0",
  "generator_version": "1",
  "bridge_contract_version": "1"
}
```

The complete record also contains the immutable release/archive URLs, MIT source
license URL, release archive hash, both input hashes, and later the compatibility
manifest hash. `ApiInputs.load(Path)` must reject missing fields, malformed 40/64
hex identities, absent files, and SHA-256 mismatches before parsing API contents.

- [ ] **Step 4: Run the provenance tests and verify GREEN**

Run the focused command from Step 2.

Expected: all provenance/missing-file/hash-mismatch/malformed-identity tests pass.

### Task 2: Build the strict immutable API model

**Files:**
- Create: `foundry-java-api-model/src/main/java/games/cafecito/foundry/api/model/JsonValue.java`
- Create: `foundry-java-api-model/src/main/java/games/cafecito/foundry/api/model/JsonParser.java`
- Create: `foundry-java-api-model/src/main/java/games/cafecito/foundry/api/model/FoundryApi.java`
- Create: `foundry-java-api-model/src/main/java/games/cafecito/foundry/api/model/FoundryApiParser.java`
- Create: `foundry-java-api-model/src/test/java/games/cafecito/foundry/api/model/FoundryApiParserTest.java`
- Create: `foundry-java-api-model/src/test/resources/fixtures/complete-api.json`

- [ ] **Step 1: Add failing parser/schema/immutability tests**

The fixture includes every accepted top-level category and nested class method,
default argument, property, signal, virtual, singleton, utility, built-in,
operator, enum/bitfield, collection/packed-array type, and native structure.

```java
FoundryApi api = FoundryApiParser.parse(fixture);
assertEquals(expectedCategoryCounts, api.categoryCounts());
assertThrows(UnsupportedOperationException.class, () -> api.entities().clear());
assertThrows(ApiInputException.class, () -> parseWithUnknownField("$.classes[0].surprise"));
assertThrows(ApiInputException.class, () -> parseDuplicateIdentity("classes/Node"));
```

- [ ] **Step 2: Run the parser test and verify RED**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :foundry-java-api-model:test --tests '*FoundryApiParserTest'
```

Expected: compilation fails because the parser/model types do not exist.

- [ ] **Step 3: Implement the minimal dependency-free strict model**

`JsonValue` is a sealed immutable value tree whose object and array constructors
defensively copy inputs. `FoundryApiParser` validates every accepted object-key
set and required value type, rejects duplicate JSON keys, unknown constructs,
unknown `api_type`/utility-category values, and malformed or duplicate stable
identities. Diagnostics use:

```text
Unknown construct at $.classes[0].surprise (entity classes/Node)
```

Stable identities use source names plus overload discriminators (`hash`, constructor
`index`, or operator right type). Named collections normalize by identity while
argument order remains semantic and unchanged. Canonical serialization sorts object
keys and normalized entity collections and never includes timestamps or paths.

- [ ] **Step 4: Run fixture and accepted-input model tests and verify GREEN**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :foundry-java-api-model:test
```

Expected: the compact fixture and the full accepted 6.7 MB API parse, normalize, and
round-trip deterministically.

### Task 3: Require exhaustive compatibility classification

**Files:**
- Create: `foundry-java-api-model/src/main/java/games/cafecito/foundry/api/model/CompatibilityManifest.java`
- Create: `foundry-java-api-model/src/test/java/games/cafecito/foundry/api/model/CompatibilityManifestTest.java`
- Create: `api/current/compatibility-manifest.json`

- [ ] **Step 1: Add failing exhaustive-classification tests**

```java
CompatibilityManifest manifest = CompatibilityManifest.supported(api);
assertEquals(api.entities().size(), manifest.entries().size());
assertTrue(manifest.entries().stream().allMatch(entry -> !entry.reasonCode().isBlank()));
assertThrows(ApiInputException.class, () -> manifest.requireComplete(api, manifest.without(firstId)));
```

The test also checks stable source identity ordering and the four allowed statuses:
`supported`, `excluded-language`, `excluded-platform`, and `excluded-upstream`.

- [ ] **Step 2: Run the classification test and verify RED**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :foundry-java-api-model:test --tests '*CompatibilityManifestTest'
```

Expected: compilation fails because compatibility classification does not exist.

- [ ] **Step 3: Implement exhaustive stable classification and check in its result**

For this model/generator contract, every source entity preserved from the accepted
Foundry-Swift category set is `supported` with reason code
`PUBLIC_FOUNDRY_EXTENSION_API`; the model still defines and validates the three
approved exclusion statuses for future reviewed differences. Missing, duplicate,
unknown-status, or blank-reason entries fail closed.

Generate `api/current/compatibility-manifest.json` from the accepted model, add its
SHA-256 to provenance, and make `ApiInputs.load` verify it.

- [ ] **Step 4: Run classification and provenance tests and verify GREEN**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :foundry-java-api-model:test
```

Expected: every parsed entity is accounted for exactly once and the checked-in
manifest hash validates.

### Task 4: Generate deterministic Java and compile it

**Files:**
- Replace: `foundry-java-generator/src/main/java/games/cafecito/foundry/generator/FoundrySourceGenerator.java`
- Create: `foundry-java-generator/src/main/java/games/cafecito/foundry/generator/GeneratedTree.java`
- Create: `foundry-java-generator/src/test/java/games/cafecito/foundry/generator/FoundrySourceGeneratorTest.java`
- Create: `foundry-java-generator/src/test/resources/fixtures/complete-api.json`
- Create: `foundry-java-generator/src/test/resources/fixtures/expected/`

- [ ] **Step 1: Add failing golden, determinism, and compiler tests**

```java
GeneratedTree first = generator.generate(api, provenance);
GeneratedTree second = generator.generate(reorderedApi, provenance);
assertEquals(first.sha256ByPath(), second.sha256ByPath());
assertGoldenTree(first);
assertEquals(0, compileWithSupportedJdk(first));
```

Assertions name each required category, verify provenance headers contain input and
manifest hashes, and reject timestamp/absolute-path text.

- [ ] **Step 2: Run the generator test and verify RED**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :foundry-java-generator:test --tests '*FoundrySourceGeneratorTest'
```

Expected: compilation fails because the deterministic generator API is absent.

- [ ] **Step 3: Implement the minimal deterministic generator**

Generate stable Java 17 metadata sources for classes/inheritance and their
methods/defaults/properties/signals/virtuals, built-ins/operators/constructors,
global enums/bitfields, singletons, utilities, native structures, collection and
packed-array type references, plus an extension-registration catalog. Names are
escaped deterministically, files are sorted by normalized source identity, and each
file starts with:

```java
// Generated by Foundry-Java generator 1.
// Foundry API SHA-256: 85e91174c1a8a48629223d6459bb2ef595ad1da405b2ce88435c24fe221aec51
// Compatibility manifest SHA-256: {provenance.compatibilityManifestSha256()}
```

- [ ] **Step 4: Run golden and full accepted-input generation twice**

Delete both output roots, generate independently, byte-compare relative paths and
contents, and compile every generated `.java` file with the Java 17 compiler API.

Expected: identical trees and zero compiler diagnostics.

### Task 5: Document, verify, and commit the checkpoint

**Files:**
- Create: `docs/api-compatibility.md`
- Modify: `docs/compatibility.md`
- Modify: `README.md`

- [ ] **Step 1: Document the accepted release and compatibility contract**

Document source release/commit/hash/license, API and ABI minimum, generator and
bridge versions, model rejection behavior, stable classification meanings and
counts, deterministic regeneration, and the fact that no Android host library or
private host JNI is consumed.

- [ ] **Step 2: Run formatting and focused gates**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew spotlessApply \
  :foundry-java-api-model:test :foundry-java-generator:test
```

Expected: exit 0.

- [ ] **Step 3: Run fresh full repository verification**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon clean check
```

Expected: exit 0 with every Java, Android, publication, provenance, compatibility,
determinism, and generated-compilation gate passing.

- [ ] **Step 4: Inspect scope and commit**

Run:

```bash
git diff --check
git status --short
git diff --name-only | rg 'Foundry-Android|libfoundry_android\.so'
```

Expected: no whitespace error, only WS5 files changed, and no forbidden Android
donor/library reference.

Commit:

```bash
git add api docs README.md foundry-java-api-model foundry-java-generator
git commit -m "Generate deterministic Foundry API models"
```

- [ ] **Step 5: Report the pre-PR checkpoint**

Report the base and commit SHA, exact RED/GREEN evidence, category/entity and
classification counts, accepted input and manifest hashes, repeat-clean byte
identity, generated Java compilation, fresh full gate output, clean status, and any
genuinely new out-of-scope epic child requirement.
