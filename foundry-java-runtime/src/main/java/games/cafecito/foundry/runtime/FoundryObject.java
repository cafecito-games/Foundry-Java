package games.cafecito.foundry.runtime;

import games.cafecito.foundry.types.Variant;
import java.lang.ref.Cleaner;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Base class for every context-bound generated Foundry object wrapper. */
public class FoundryObject implements AutoCloseable {
    private static final Cleaner CLEANER = Cleaner.create();

    private final FoundryBindingContext context;
    private final ObjectLease lease;
    private final Cleaner.Cleanable cleanable;

    protected FoundryObject(FoundryBindingContext context, ObjectLease lease) {
        this.context = Objects.requireNonNull(context, "context");
        this.lease = Objects.requireNonNull(lease, "lease");
        if (context.contextHandle() != lease.contextHandle()) {
            throw new IllegalArgumentException(
                    "Object lease belongs to context "
                            + lease.contextHandle()
                            + ", not "
                            + context.contextHandle());
        }
        cleanable = CLEANER.register(this, lease);
    }

    public final FoundryBindingContext context() {
        requireAlive();
        return context;
    }

    public final long objectHandle() {
        requireAlive();
        return lease.objectHandle();
    }

    public final boolean isAlive() {
        return lease.isAlive();
    }

    protected final void requireAlive() {
        lease.requireAlive();
    }

    protected final Variant call(String methodIdentity, Variant... arguments) {
        requireAlive();
        String checkedMethod = Objects.requireNonNull(methodIdentity, "methodIdentity");
        List<Variant> checkedArguments =
                List.copyOf(Arrays.asList(Objects.requireNonNull(arguments, "arguments").clone()));
        return context.call(lease.objectHandle(), checkedMethod, checkedArguments);
    }

    @Override
    public final void close() {
        context.invalidateObject(lease.objectHandle());
        cleanable.clean();
    }

    final void runCleanerForTesting() {
        cleanable.clean();
    }

    final void invalidate() {
        lease.invalidate();
    }

    final ObjectLease lease() {
        return lease;
    }
}
