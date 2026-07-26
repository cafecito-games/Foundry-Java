# Java Invalidation Subscriptions Design

## Context

The Java runtime exposes `FoundryObject.isAlive()` and deterministic object/context invalidation,
but consumers cannot observe the transition without polling. Kotlin signal awaiting needs a
Java-first notification contract so a suspended wait can disconnect promptly when its owner dies.
The contract must remain host-neutral and must not add Android, JNI, Kotlin, coroutine, or executor
dependencies.

## Public API

`ObjectLease` and `FoundryObject` expose the same registration shape:

```java
public FoundryInvalidationSubscription onInvalidated(Runnable listener);
```

`FoundryObject.onInvalidated` delegates to its lease. The returned public final
`FoundryInvalidationSubscription` implements `AutoCloseable`:

```java
public boolean isActive();
public void close();
```

`close()` is idempotent. `isActive()` is true only while the listener remains eligible for a future
invalidation snapshot.

## State And Linearization

`ObjectLease` owns an insertion-ordered listener map under its existing `stateLock`. Registration,
removal, and the first alive-to-invalid transition linearize under that same lock.

- If subscription removal deletes the listener before invalidation snapshots the map, the listener
  is not called.
- If invalidation snapshots the listener first, delivery is committed exactly once. A concurrent
  `close()` cannot retract it.
- The subscription is inactive after either removal or snapshot.
- Repeated `invalidate()`, `run()`, wrapper close, object invalidation, and context close do not
  create a second delivery.

Subscribing after the lease is dead invokes the listener synchronously before
`onInvalidated` returns and returns an inactive subscription.

## Locking And Delivery

The invalidating operation marks the lease dead, detaches the listener batch, and deactivates its
subscriptions while holding `ObjectLease.stateLock`. It then releases that lock before invoking any
listener.

`FoundryBindingContext.invalidateObject`, wrapper release, failed wrapper construction, and context
close detach leases or update context collections while holding `lifecycleLock`, then release that
lock before a lease can invoke user listeners. Internal liveness checks performed while the context
lock is held do not deliver callbacks.

Callbacks execute on the thread that performs invalidation or, for an already-dead subscription, on
the subscribing thread. The runtime does not dispatch to another thread. Per-lease listeners run in
registration order. Object ordering during context-wide close is unspecified.

## Failures And Release Ordering

One listener failure must not prevent later listeners, object release, or context shutdown. Each
`Throwable` is contained and reported through the runtime's existing lifecycle failure convention:
`FoundryEngine.reportCallbackException(contextHandle, 0, failure)`, where callback handle zero is
already used by `ObjectLease` for non-callback lifecycle/cleaner failures. A failure from the
reporting hook is also contained.

The lease becomes observably dead before listeners run. Listener delivery completes before the
lease performs a required reference-counted engine release.

## Verification

RED-first Java tests cover:

- already-dead immediate notification and inactive return;
- active registration, idempotent removal, and no late callback;
- object invalidation, wrapper close, and binding-context close;
- exact-once delivery across invalidate/close/remove races;
- committed-delivery versus removal-wins linearization;
- cross-thread close and deterministic barrier-driven stress;
- user callbacks running after both lifecycle locks are released;
- reentrant subscribe/remove/close behavior;
- per-listener failure containment and continued delivery/release;
- `FoundryObject` delegation and direct `ObjectLease` registration.

The public runtime API baseline and memory/threading documentation record the new contract. Full
Java 17, Javadoc, publication, dependency-lock, configuration-cache, independent review, and Cursor
review gates must pass before publication.
