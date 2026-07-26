# Extension authoring

Implement `games.cafecito.foundry.api.FoundryExtension` in Java, then pass that implementation to
the Android host adapter. Avoid host internals and native symbols: the supported integration boundary
is the public Java ABI only.

For compile-time extension classes, declaration validation, deterministic registries, and generated
trampolines, see [Java extension authoring](java-authoring.md).
