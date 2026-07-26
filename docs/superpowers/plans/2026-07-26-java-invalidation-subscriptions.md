# Java Invalidation Subscriptions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a race-safe, Java-first invalidation subscription API for `ObjectLease` and
`FoundryObject`, with deterministic exactly-once delivery outside runtime lifecycle locks.

**Architecture:** `ObjectLease` owns ordered listener entries under its existing state lock.
Invalidation is split into an internal transition that marks the lease dead and detaches a listener
batch, followed by delivery and optional native release after caller locks are released.
`FoundryBindingContext` collects those detached transitions while holding its lifecycle lock and
executes them afterward. A public closeable subscription delegates removal and activity checks to
the lease state that defines the race linearization point.

**Tech Stack:** Java 17, Gradle, JUnit 5, existing Foundry-Java API-baseline shell tooling.

---

## Task 1: Prove the public registration and removal contract

**Files:**

- Modify: `foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/ObjectLifecycleTest.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryInvalidationSubscription.java`
- Modify: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/ObjectLease.java`
- Modify: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryObject.java`

### Step 1: Write the failing API and basic-lifecycle tests

Add focused tests that:

- subscribe through both `ObjectLease` and `FoundryObject`;
- observe active registration and idempotent removal;
- prove removal prevents later notification;
- prove object invalidation and wrapper close deliver once;
- prove already-dead registration invokes synchronously before return and returns inactive;
- reject a null listener deterministically.

Use direct counters and captured thread IDs; do not sleep.

### Step 2: Run the focused tests and confirm RED

Run:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin:$PATH \
./gradlew --no-daemon :foundry-java-runtime:test \
  --tests 'games.cafecito.foundry.runtime.ObjectLifecycleTest' --rerun-tasks
```

Expected: compilation fails because the subscription type and registration methods do not exist.

### Step 3: Implement the smallest public surface and lease-owned state

Create a public final `FoundryInvalidationSubscription implements AutoCloseable` with:

```java
public boolean isActive();
@Override public void close();
```

Use a package-private controller owned by `ObjectLease`; the public token must not define an
independent race. Add:

```java
public FoundryInvalidationSubscription onInvalidated(Runnable listener);
```

to `ObjectLease`, and a final delegating method to `FoundryObject`.

Inside `ObjectLease`, maintain insertion-ordered listener entries under `stateLock`. Registration,
removal, snapshot, and activity checks must linearize under that lock. Snapshot deactivates all
tokens before releasing the lock. Already-dead registration invokes through the same contained
single-listener delivery helper after releasing the lock and returns an inactive token.

### Step 4: Run the focused tests and confirm GREEN

Run the Task 1 command. Expected: all `ObjectLifecycleTest` tests pass.

### Step 5: Commit the public contract checkpoint

```sh
git add docs/superpowers/specs/2026-07-26-java-invalidation-subscriptions-design.md \
  docs/superpowers/plans/2026-07-26-java-invalidation-subscriptions.md \
  foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryInvalidationSubscription.java \
  foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/ObjectLease.java \
  foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryObject.java \
  foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/ObjectLifecycleTest.java
git commit -m "Add Java invalidation subscriptions"
```

## Task 2: Detach context transitions from callback delivery

**Files:**

- Modify: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/ObjectLease.java`
- Modify: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryBindingContext.java`
- Modify: `foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/ObjectLifecycleTest.java`
- Modify: `foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/CallbackShutdownTest.java`

### Step 1: Write failing context, ordering, and lock-release tests

Add deterministic tests that prove:

- context close notifies each live lease once, marks the context and leases dead first, then releases;
- engine object invalidation notifies once and releases a retained object after notification;
- wrapper factory code can subscribe and then throw, while cleanup notification and release preserve
  the original factory failure and happen outside the context lifecycle lock;
- listeners can reenter context and lease APIs without deadlock;
- callbacks can use a second thread to acquire both lifecycle state and lease state while the
  callback waits on the result, proving neither lock remains held;
- bridge deinitialization triggers invalidation before native release and no late callback occurs.

Coordinate threads with `CountDownLatch`, `CyclicBarrier`, or futures. Timeouts are deadlock guards,
not scheduling mechanisms.

### Step 2: Run the two focused suites and confirm RED

Run:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin:$PATH \
./gradlew --no-daemon :foundry-java-runtime:test \
  --tests 'games.cafecito.foundry.runtime.ObjectLifecycleTest' \
  --tests 'games.cafecito.foundry.runtime.CallbackShutdownTest' --rerun-tasks
```

Expected: at least the factory-failure or context lock-release test fails or times out with the
current in-lock lease cleanup.

### Step 3: Introduce explicit transition and detached execution

Add a package-private immutable transition/batch type in `ObjectLease` that carries:

- the detached listener batch;
- whether the native reference must be released;
- one idempotent detached execution path that delivers listeners, then releases.

The state-changing method may run while `FoundryBindingContext.lifecycleLock` is held, but it must
never invoke listener code or engine release. `invalidate()`, `run()`, and public liveness-triggered
invalidation use the same transition machinery and execute only after their caller locks are clear.

Refactor `FoundryBindingContext.invalidateObject`, `releaseWrapper`, and `close` to update context
collections and collect lease transitions under `lifecycleLock`, then execute them afterward.
Refactor `bind()` so a wrapper factory failure is captured inside the synchronized section, then its
lease transition executes outside that section before the original `RuntimeException` or `Error` is
re-thrown. Avoid listener-capable `isAlive()` calls while the context lock is held by using an
internal non-delivering state check after the engine validity check.

### Step 4: Run the focused suites and confirm GREEN

Run the Task 2 command. Expected: both suites pass without deadlock.

### Step 5: Commit the context-lock checkpoint

```sh
git add foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/ObjectLease.java \
  foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryBindingContext.java \
  foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/ObjectLifecycleTest.java \
  foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/CallbackShutdownTest.java
git commit -m "Detach invalidation delivery from lifecycle locks"
```

## Task 3: Prove race linearization and failure containment

**Files:**

- Modify: `foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/ObjectLifecycleTest.java`
- Modify: `foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/CallbackShutdownTest.java`
- Modify: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/ObjectLease.java`

### Step 1: Add the remaining RED race matrix

Add deterministic tests for:

- removal-wins: closing the token before snapshot yields zero calls;
- snapshot-wins: after the first callback enters, concurrent token close cannot retract delivery;
- simultaneous remove/invalidate iterations yield only zero or one call, never more, and leave the
  token inactive;
- simultaneous lease invalidation/context close still delivers exactly once;
- cross-thread wrapper close invokes on the closing thread;
- a listener can subscribe, remove, invalidate, and close reentrantly without a second delivery;
- one throwing listener is reported with callback handle zero but does not stop later listeners or
  native release;
- immediate-dead registration contains and reports a throwing listener without throwing from
  `onInvalidated`;
- a throwing failure reporter is also contained.

Use barriers/latches for every race; do not sleep.

### Step 2: Run focused suites and observe RED where behavior is incomplete

Run the Task 2 command. Expected: any incomplete race/failure behavior fails with a precise
assertion.

### Step 3: Complete contained delivery and race semantics

Centralize listener invocation in one helper. For each listener, catch every `Throwable`, call
`engine.reportCallbackException(contextHandle, 0, failure)`, and catch every reporter failure.
Never abort a batch. Ensure all first-transition entry points detach the same batch exactly once.

### Step 4: Run focused suites and confirm GREEN

Run the Task 2 command. Expected: all race, reentrancy, and failure tests pass.

### Step 5: Commit the concurrency checkpoint

```sh
git add foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/ObjectLease.java \
  foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/ObjectLifecycleTest.java \
  foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/CallbackShutdownTest.java
git commit -m "Harden invalidation subscription races"
```

## Task 4: Publish the API and document the contract

**Files:**

- Modify: `foundry-java-runtime/api/foundry-java-runtime.api`
- Modify: `docs/memory-and-threading.md`
- Modify: `docs/memory.md`
- Modify: public Javadocs in the Task 1 production files

### Step 1: Run the API gate and confirm the baseline is stale

Run:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin:$PATH \
./gradlew --no-daemon :foundry-java-runtime:verifyRuntimeApi
```

Expected: the API comparison reports the new public type and methods.

### Step 2: Regenerate the public inventory and update docs

Compile runtime classes, then use:

```sh
gradle/verify-runtime-api.sh foundry-java-runtime/build/classes/java/main -
```

Replace the baseline with the generated inventory. Document:

- subscription lifetime and removal/snapshot linearization;
- synchronous calling-thread delivery;
- callbacks outside lifecycle locks and safe reentrancy;
- inactive return for already-dead objects;
- listener failure containment and delivery-before-release ordering.

### Step 3: Run API, Javadoc, and focused tests

Run:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin:$PATH \
./gradlew --no-daemon :foundry-java-runtime:check \
  :foundry-java-runtime:javadoc --rerun-tasks
```

Expected: API inventory, Javadoc, and runtime tests pass.

### Step 4: Commit documentation and API inventory

```sh
git add foundry-java-runtime/api/foundry-java-runtime.api \
  foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime \
  docs/memory-and-threading.md docs/memory.md
git commit -m "Document Java invalidation subscriptions"
```

## Task 5: Verify, integrate current main, and publish

**Files:**

- Verify all files changed above.

### Step 1: Run local diff hygiene and focused verification

```sh
git diff --check origin/main...HEAD
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin:$PATH \
./gradlew --no-daemon :foundry-java-runtime:test \
  --tests 'games.cafecito.foundry.runtime.ObjectLifecycleTest' \
  --tests 'games.cafecito.foundry.runtime.CallbackShutdownTest' --rerun-tasks
```

### Step 2: Rebase on the current `origin/main`

Fetch and rebase after any dependency PR, especially Foundry-Java #25, merges. Resolve conflicts
without changing the approved contract. Rerun Task 5 Step 1 after rebase.

### Step 3: Run the full Java 17/configuration-cache gate

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin:$PATH \
./gradlew --no-daemon clean check
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin:$PATH \
./gradlew --no-daemon check
```

Expected: both passes are green and the second reuses configuration cache.

### Step 4: Obtain exact independent and Cursor reviews

Request independent review of committed `origin/main...HEAD`, address every valid finding with
RED/GREEN evidence, and rerun verification. Then run the `cursor-review` skill in foreground,
read-only mode against exact base `origin/main`. Repeat until the final output contains exactly
`RESULT: clean`.

### Step 5: Open, converge, and merge the PR

Push `issue-26`; open a PR targeting `main` with `Closes #26`; wait for checks and review to converge.
Only then enable auto-merge. Confirm the PR merged, issue #26 closed, and project item Done.

### Step 6: Clean the worktree and branch

After merge, remove the issue worktree and delete the local issue branch using safe, exact targets.
Confirm no issue worktree or local issue branch remains.
