# Extension authoring

Implement `games.cafecito.foundry.api.FoundryExtension` in Java, then pass that implementation to
the Android host adapter. Avoid host internals and native symbols: the supported integration boundary
is the public Java ABI only.
