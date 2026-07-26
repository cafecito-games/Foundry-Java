# Memory and lifecycle

The host-neutral runtime does not retain Android objects. Android host adapters retain only an
application context and should release project-specific references according to their application
lifecycle. Do not transfer ownership to native Foundry internals.
