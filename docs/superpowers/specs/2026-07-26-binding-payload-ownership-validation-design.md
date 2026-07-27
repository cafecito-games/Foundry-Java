# Binding Payload Ownership Validation Design

**Status:** Approved

## Context

The Foundry-Java Gradle plugin gives `RegistryIndexTask` the complete Android
variant runtime graph. That is intentional: generated module descriptors may
arrive through direct or transitive dependencies. The merged scanner currently
rejects `libfoundry_android.so` in every archive before it determines whether
the archive owns any Foundry-Java binding payload. A normal Foundry application
therefore fails when its required host AAR and the separate Foundry-Java binding
AAR are both present.

Foundry-Java must never package, link, load, or redistribute the host library.
That prohibition applies to Foundry-Java binding claimants, not to unrelated
host artifacts that the final Android application must preserve.

Foundry-Android remains a read-only source donor and is not used or modified by
this work.

## Selected Approach

Keep the full variant runtime graph as both the descriptor and payload scan
input. Scan every regular archive completely and collect:

- the fixed `FoundryJava.foundryextension` configuration, including its
  supported `classes.jar` location;
- every `jni/<abi>/libfoundry_java.so` bridge entry;
- every root or nested archive entry whose normalized name is
  `libfoundry_android.so`.

Only after the scan is complete, classify an archive as a Foundry-Java binding
claimant when it contains the fixed configuration and/or at least one bridge
ABI. Reject a claimant that also contains any forbidden host-library entry.
The diagnostic names the claimant artifact and every offending entry in sorted
order.

An archive with no configuration and no bridge is not a binding claimant. Its
host library is ignored by Foundry-Java validation and remains available to
normal Android packaging. Any module descriptors in that same archive remain
eligible for the independent descriptor aggregation pass.

## Rejected Approaches

### Narrow the Gradle task inputs

Restricting payload inputs to a guessed dependency or coordinate would lose
transitive module descriptors and introduce a Foundry-side configuration
override. It would also make behavior depend on repository topology rather than
payload ownership.

### Artifact-name or coordinate allowlists

Names and coordinates are not semantic ownership. Local files, Maven artifacts,
and republished dependencies must behave identically. No filename, group,
module, or version heuristic is permitted.

### Allow the host library everywhere

Removing the check would permit the Foundry-Java binding AAR or a split
configuration/bridge claimant to redistribute the host runtime. That violates
the repository and ABI boundary.

## Validation and Failure Contract

Configuration-only, bridge-only, and combined binding claimants containing the
host library fail before descriptor graph validation or generated output
publication. Diagnostics are deterministic and include the absolute artifact
identity and all sorted forbidden entry names.

Existing validation remains unchanged:

- exactly one bridge claimant and one configuration claimant;
- bridge and configuration must belong to the same artifact;
- duplicate root/nested configuration entries fail;
- duplicate bridge ABI entries fail;
- requested ABI values remain restricted and every requested ABI must exist;
- descriptor parsing, duplicate module/registry detection, contract matching,
  deterministic index generation, and direct bootstrap generation remain
  fail-closed.

## Dependency Topologies

The regression proof covers both ways WS11 consumes Foundry-Java:

1. local file dependencies containing an ordinary host AAR plus a separate
   binding AAR;
2. a staged Maven repository whose plugin marker depends on the implementation
   plugin and whose Android AAR depends on runtime, with runtime depending on
   API-model and annotations.

Neither topology may require an internal task-input override. The classifier
uses archive contents only.

## Tests

Focused TestKit coverage requires:

- a host-only AAR containing `libfoundry_android.so` succeeds alongside a valid
  binding AAR;
- a host-only AAR may also contribute a module descriptor;
- configuration-only, bridge-only, and combined claimants containing one or
  more host-library entries fail with artifact and every sorted entry;
- duplicate/split/configuration/bridge/requested-ABI failures remain unchanged;
- AGP 8.10 local and staged-Maven builds generate the fixed configuration,
  deterministic index, and direct bootstrap;
- the assembled custom-ID/minified APK preserves the requested host ABI and the
  selected `libfoundry_java.so` ABI;
- AGP 8.9.1 remains compatible;
- configuration-cache storage and reuse remain clean.

The final gate runs the focused plugin suite, publication/repository contracts,
and `./gradlew --no-daemon clean check`, followed by independent exact-head
review and Cursor `RESULT: clean`.
