package games.cafecito.foundry.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FoundryRegistrationPlanMemberTest {
    @Test
    void canonicalizesMembersByKindNameAndTypedDetails() {
        FoundryClassDescriptor descriptor =
                type(
                        List.of(
                                property("zeta_property", "getZeta", "", -1),
                                constant("zeta_constant", "", 7L, false),
                                constant("alpha_constant", "Flags", 2L, true),
                                method("zeta_method"),
                                property("alpha_property", "getAlpha", "setAlpha", 3),
                                method("alpha_method")));

        FoundryRegistrationPlan plan = FoundryRegistrationPlan.create(bootstrap(descriptor));

        assertEquals(
                List.of(
                        "constant:alpha_constant:Flags:2:true",
                        "constant:zeta_constant::7:false",
                        "method:alpha_method",
                        "method:zeta_method",
                        "property:alpha_property:getAlpha:setAlpha:3",
                        "property:zeta_property:getZeta::-1"),
                plan.orderedClasses().get(0).members().stream()
                        .map(FoundryRegistrationPlanMemberTest::identity)
                        .toList());
    }

    @Test
    void rejectsDuplicateExportedMemberNamesBeforeEngineMutation() {
        FoundryClassDescriptor descriptor =
                type(List.of(method("shared"), property("shared", "getShared", "", -1)));
        FoundryRegistryBootstrap bootstrap = bootstrap(descriptor);
        AtomicInteger engineCreations = new AtomicInteger();

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new FoundryRegistryCoordinator(
                                bootstrap,
                                ignored -> {
                                    engineCreations.incrementAndGet();
                                    throw new AssertionError("must not create the engine");
                                }));

        assertEquals(0, engineCreations.get());
    }

    @Test
    void rejectsIncoherentLegacyKindsBeforeEngineMutation() {
        FoundryClassDescriptor descriptor =
                type(List.of(new FoundryMemberDescriptor("unknown", "value", "value", "int")));
        FoundryRegistryBootstrap bootstrap = bootstrap(descriptor);
        AtomicInteger engineCreations = new AtomicInteger();

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new FoundryRegistryCoordinator(
                                bootstrap,
                                ignored -> {
                                    engineCreations.incrementAndGet();
                                    throw new AssertionError("must not create the engine");
                                }));

        assertEquals(0, engineCreations.get());
    }

    private static String identity(FoundryMemberDescriptor member) {
        if (member.details() instanceof FoundryConstantDetails constant) {
            return member.kind()
                    + ":"
                    + member.foundryName()
                    + ":"
                    + constant.enumName()
                    + ":"
                    + constant.value()
                    + ":"
                    + constant.bitfield();
        }
        if (member.details() instanceof FoundryPropertyDetails property) {
            return member.kind()
                    + ":"
                    + member.foundryName()
                    + ":"
                    + property.getter()
                    + ":"
                    + property.setter()
                    + ":"
                    + property.index();
        }
        return member.kind() + ":" + member.foundryName();
    }

    private static FoundryMemberDescriptor method(String name) {
        return new FoundryMemberDescriptor("method", name, name, "void()");
    }

    private static FoundryMemberDescriptor constant(
            String name, String enumName, long value, boolean bitfield) {
        return new FoundryMemberDescriptor(
                "constant",
                name,
                name,
                "long",
                new FoundryConstantDetails(enumName, value, bitfield));
    }

    private static FoundryMemberDescriptor property(
            String name, String getter, String setter, int index) {
        return new FoundryMemberDescriptor(
                "property",
                name,
                name,
                "int",
                new FoundryPropertyDetails(getter, setter, index, "", "", "", ""));
    }

    private static FoundryRegistryBootstrap bootstrap(FoundryClassDescriptor descriptor) {
        FoundryModuleDescriptor module =
                new FoundryModuleDescriptor(
                        FoundryModuleDescriptor.CURRENT_FORMAT,
                        "demo",
                        "generated.demo",
                        FoundryRuntime.API_SHA256,
                        FoundryRuntime.GENERATOR_VERSION,
                        FoundryRuntime.RUNTIME_CONTRACT_VERSION,
                        FoundryRuntime.BRIDGE_CONTRACT_VERSION,
                        List.of(descriptor));
        return new FoundryRegistryBootstrap(List.of(() -> module));
    }

    private static FoundryClassDescriptor type(List<FoundryMemberDescriptor> members) {
        return new FoundryClassDescriptor(
                "example.Demo", "Demo", "Node", "SCENE", List.of(), new NoOpAccess(), members);
    }

    private static final class NoOpAccess implements FoundryExtensionAccess {
        @Override
        public Object construct(FoundryBindingContext context, ObjectLease lease) {
            return new Object();
        }

        @Override
        public Object invoke(Object target, String name, Object[] arguments) {
            return null;
        }

        @Override
        public Object getProperty(Object target, String name) {
            return null;
        }

        @Override
        public void setProperty(Object target, String name, Object value) {}
    }
}
