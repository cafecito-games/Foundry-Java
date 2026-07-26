# Memory and lifecycle

The host-neutral runtime does not retain Android objects. Android host adapters retain only an
application context and should release project-specific references according to their application
lifecycle. Do not transfer ownership to native Foundry internals.

The binding runtime's object, pointer, Variant, signal, invalidation, close, and concurrency
contracts are specified in [Runtime memory and threading](memory-and-threading.md).
