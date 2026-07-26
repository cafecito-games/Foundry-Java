# Memory and lifecycle

The host-neutral runtime does not retain Android objects. Android host adapters retain only an
application context and should release project-specific references according to their application
lifecycle. Do not transfer ownership to native Foundry internals.

The binding runtime's object, pointer, Variant, signal, invalidation, close, and concurrency
contracts are specified in [Runtime memory and threading](memory-and-threading.md).

Native bridge contexts are opaque, nonzero, generation-bound handles. A callback acquires a lease on
its context before it enters Java, so callbacks may safely reenter the bridge. Closing a context
first removes it from lookup and rejects new leases, then waits for active leases to drain before
calling Java deinitialization and invalidation. Stale or cross-generation handles return a safe
default.

Core shutdown closes every remaining context before releasing callback and class-loader global
references. It then clears the public Foundry interface table and class-library pointer. Shutdown
from inside the same active callback is rejected because waiting for that callback would deadlock;
another thread may perform shutdown once the callback returns.
