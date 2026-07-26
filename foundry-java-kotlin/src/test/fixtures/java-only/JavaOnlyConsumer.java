package games.cafecito.foundry.fixtures.javaonly;

import games.cafecito.foundry.runtime.FoundryBindingContext;
import games.cafecito.foundry.runtime.FoundryObject;
import games.cafecito.foundry.runtime.ObjectLease;
import games.cafecito.foundry.runtime.ObjectOwnership;

/** Proves the Java runtime remains usable without the optional Kotlin artifact. */
public final class JavaOnlyConsumer extends FoundryObject {
    public JavaOnlyConsumer(FoundryBindingContext context, ObjectLease lease) {
        super(context, lease);
    }

    public static JavaOnlyConsumer bind(FoundryBindingContext context, long objectHandle) {
        return context.bind(
                objectHandle,
                ObjectOwnership.BORROWED,
                JavaOnlyConsumer.class,
                JavaOnlyConsumer::new);
    }
}
