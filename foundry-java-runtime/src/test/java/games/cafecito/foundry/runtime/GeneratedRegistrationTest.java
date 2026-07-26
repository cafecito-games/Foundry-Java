package games.cafecito.foundry.runtime;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import games.cafecito.foundry.generated.GeneratedRegistration;
import games.cafecito.foundry.generated.classes.Node3D;
import org.junit.jupiter.api.Test;

class GeneratedRegistrationTest {
    @Test
    void registerAllLetsABaseBindPublishTheMostDerivedGeneratedWrapper() {
        ObjectLifecycleTest.CountingEngine engine = new ObjectLifecycleTest.CountingEngine();
        engine.valid(7);
        engine.nativeTypes.put(7L, "Node3D");
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        GeneratedRegistration.registerAll(context);

        games.cafecito.foundry.generated.classes.Object throughBase =
                games.cafecito.foundry.generated.classes.Object.bind(context, 7);
        Node3D throughDerived = Node3D.bind(context, 7);

        assertInstanceOf(Node3D.class, throughBase);
        assertSame(throughBase, throughDerived);
    }

    @Test
    void singletonBindReturnsAndReusesTheCanonicalRegisteredEngineWrapper() {
        ObjectLifecycleTest.CountingEngine engine = new ObjectLifecycleTest.CountingEngine();
        engine.valid(1);
        engine.nativeTypes.put(1L, "Engine");
        FoundryBindingContext context = new FoundryBindingContext(11, engine);
        GeneratedRegistration.registerAll(context);

        games.cafecito.foundry.generated.classes.Engine singleton =
                games.cafecito.foundry.generated.singletons.Engine.bind(context);
        games.cafecito.foundry.generated.classes.Engine direct =
                games.cafecito.foundry.generated.classes.Engine.bind(context, 1);

        assertSame(singleton, direct);
    }
}
