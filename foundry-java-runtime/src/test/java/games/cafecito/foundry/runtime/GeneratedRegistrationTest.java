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
                games.cafecito.foundry.generated.classes.Object.bind(
                        context, 7, ObjectOwnership.BORROWED);
        Node3D throughDerived = Node3D.bind(context, 7, ObjectOwnership.BORROWED);

        assertInstanceOf(Node3D.class, throughBase);
        assertSame(throughBase, throughDerived);
    }
}
