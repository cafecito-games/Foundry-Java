package games.cafecito.foundry.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ObjectLifecycleTest {
    @Test
    void returnsOneCanonicalWrapperPerContextHandleAndRejectsClassMismatches() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);

        TestObject first =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);
        TestObject second =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);
        FoundryBindingContext secondContext = new FoundryBindingContext(12, engine);
        TestObject otherContext =
                secondContext.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);

        assertSame(first, second);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        context.bind(
                                7, ObjectOwnership.BORROWED, OtherObject.class, OtherObject::new));
        org.junit.jupiter.api.Assertions.assertNotSame(first, otherContext);
        assertEquals(7, first.objectHandle());
        assertEquals(11, first.context().contextHandle());
    }

    @Test
    void cacheHitUpgradesBorrowedOwnershipExactlyOnceAndNeverDowngrades() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);

        TestObject borrowed =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);
        TestObject retained =
                context.bind(
                        7, ObjectOwnership.REFERENCE_COUNTED, TestObject.class, TestObject::new);
        TestObject borrowedAgain =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);

        assertSame(borrowed, retained);
        assertSame(retained, borrowedAgain);
        assertEquals(1, engine.retains.get());

        borrowed.close();
        retained.close();
        assertEquals(1, engine.releases.get());
    }

    @Test
    void referenceCountedFirstNeverRetainsAgainForBorrowedAliases() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);

        TestObject retained =
                context.bind(
                        7, ObjectOwnership.REFERENCE_COUNTED, TestObject.class, TestObject::new);
        TestObject borrowed =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);

        assertSame(retained, borrowed);
        assertEquals(1, engine.retains.get());
        context.invalidateObject(7);
        assertEquals(1, engine.releases.get());
    }

    @Test
    void firstBaseRequestResolvesAndPublishesTheMostDerivedRegisteredWrapper() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        engine.nativeTypes.put(7L, "DerivedObject");
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        context.registerObjectType("TestObject", TestObject.class, TestObject::new);
        context.registerObjectType("DerivedObject", DerivedObject.class, DerivedObject::new);

        TestObject throughBase =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);
        DerivedObject throughDerived =
                context.bind(7, ObjectOwnership.BORROWED, DerivedObject.class, DerivedObject::new);

        assertTrue(throughBase instanceof DerivedObject);
        assertSame(throughBase, throughDerived);
    }

    @Test
    void rejectsNullOrInvalidObjectHandlesBeforePublication() {
        CountingEngine engine = new CountingEngine();
        FoundryBindingContext context = new FoundryBindingContext(11, engine);

        assertThrows(
                IllegalArgumentException.class,
                () -> context.bind(0, ObjectOwnership.BORROWED, TestObject.class, TestObject::new));
        assertThrows(
                FoundryObjectDisposedException.class,
                () -> context.bind(9, ObjectOwnership.BORROWED, TestObject.class, TestObject::new));
    }

    @Test
    void borrowedInvalidationNeverReleasesNativeOwnership() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        TestObject object =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);

        context.invalidateObject(7);

        assertFalse(object.isAlive());
        assertThrows(FoundryObjectDisposedException.class, object::objectHandle);
        assertEquals(0, engine.releases.get());
    }

    @Test
    void referenceCountedObjectsRetainAndReleaseExactlyOnce() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        TestObject first =
                context.bind(
                        7, ObjectOwnership.REFERENCE_COUNTED, TestObject.class, TestObject::new);
        TestObject alias =
                context.bind(
                        7, ObjectOwnership.REFERENCE_COUNTED, TestObject.class, TestObject::new);

        first.close();
        alias.close();
        first.runCleanerForTesting();

        assertEquals(1, engine.retains.get());
        assertEquals(1, engine.releases.get());
        assertFalse(first.isAlive());
        assertThrows(FoundryObjectDisposedException.class, first::objectHandle);
    }

    @Test
    void shutdownInvalidatesBeforeReleasingAndRejectsNewWrappers() {
        CountingEngine engine = new CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        engine.observedContext = context;
        TestObject borrowed =
                context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new);
        engine.valid(8);
        TestObject retained =
                context.bind(
                        8, ObjectOwnership.REFERENCE_COUNTED, TestObject.class, TestObject::new);

        context.close();

        assertFalse(context.isAlive());
        assertFalse(borrowed.isAlive());
        assertFalse(retained.isAlive());
        assertEquals(1, engine.releases.get());
        assertTrue(engine.releasedAfterContextInvalidation);
        assertThrows(
                FoundryObjectDisposedException.class,
                () -> context.bind(7, ObjectOwnership.BORROWED, TestObject.class, TestObject::new));
    }

    static class TestObject extends FoundryObject {
        TestObject(FoundryBindingContext context, ObjectLease lease) {
            super(context, lease);
        }
    }

    static final class DerivedObject extends TestObject {
        DerivedObject(FoundryBindingContext context, ObjectLease lease) {
            super(context, lease);
        }
    }

    static final class OtherObject extends FoundryObject {
        OtherObject(FoundryBindingContext context, ObjectLease lease) {
            super(context, lease);
        }
    }

    static final class CountingEngine extends NoOpEngine {
        final ConcurrentHashMap<Long, Boolean> valid = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Long, String> nativeTypes = new ConcurrentHashMap<>();
        final AtomicInteger retains = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();
        volatile FoundryBindingContext observedContext;
        volatile boolean releasedAfterContextInvalidation;

        void valid(long handle) {
            valid.put(handle, true);
        }

        @Override
        public boolean isObjectValid(long contextHandle, long objectHandle) {
            return valid.getOrDefault(objectHandle, false);
        }

        @Override
        public String objectType(long contextHandle, long objectHandle) {
            return nativeTypes.getOrDefault(objectHandle, "");
        }

        @Override
        public void retain(long contextHandle, long objectHandle) {
            retains.incrementAndGet();
        }

        @Override
        public void release(long contextHandle, long objectHandle) {
            releases.incrementAndGet();
            FoundryBindingContext context = observedContext;
            releasedAfterContextInvalidation = context == null || !context.isAlive();
        }
    }
}
