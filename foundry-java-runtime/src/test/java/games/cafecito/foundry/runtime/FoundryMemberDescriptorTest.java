package games.cafecito.foundry.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;

class FoundryMemberDescriptorTest {
    @Test
    void preservesTheExactLegacyFourStringConstructor() throws ReflectiveOperationException {
        Constructor<FoundryMemberDescriptor> constructor =
                FoundryMemberDescriptor.class.getConstructor(
                        String.class, String.class, String.class, String.class);

        FoundryMemberDescriptor descriptor =
                constructor.newInstance("method", "run", "run", "void()");

        assertEquals("method", descriptor.kind());
        assertEquals("run", descriptor.foundryName());
        assertEquals("run", descriptor.javaName());
        assertEquals("void()", descriptor.signature());
        assertEquals(FoundryMemberDetails.none(), descriptor.details());
    }

    @Test
    void constantsRetainSignedLongValuesAndBitfieldMetadata() {
        FoundryMemberDescriptor minimum =
                new FoundryMemberDescriptor(
                        "constant",
                        "MINIMUM",
                        "MINIMUM",
                        "long",
                        new FoundryConstantDetails("", Long.MIN_VALUE, false));
        FoundryMemberDescriptor highBit =
                new FoundryMemberDescriptor(
                        "constant",
                        "HIGH_BIT",
                        "HIGH_BIT",
                        "long",
                        new FoundryConstantDetails("Flags", Long.MIN_VALUE, true));

        assertEquals(Long.MIN_VALUE, ((FoundryConstantDetails) minimum.details()).value());
        assertFalse(((FoundryConstantDetails) minimum.details()).bitfield());
        assertEquals("Flags", ((FoundryConstantDetails) highBit.details()).enumName());
        assertTrue(((FoundryConstantDetails) highBit.details()).bitfield());
    }

    @Test
    void constantsRejectInvalidBitfieldMetadata() {
        assertThrows(
                IllegalArgumentException.class, () -> new FoundryConstantDetails("", 1L, true));
        assertThrows(
                IllegalArgumentException.class, () -> new FoundryConstantDetails(" ", 1L, false));
    }

    @Test
    void propertiesRetainAccessIndexAndGroupingMetadata() {
        FoundryPropertyDetails details =
                new FoundryPropertyDetails(
                        "getValue", "", 7, "Physics", "physics_", "Advanced", "advanced_");
        FoundryMemberDescriptor descriptor =
                new FoundryMemberDescriptor("property", "value", "value", "int", details);

        assertEquals("getValue", details.getter());
        assertEquals("", details.setter());
        assertTrue(details.readOnly());
        assertEquals(7, details.index());
        assertEquals("Physics", details.groupName());
        assertEquals("physics_", details.groupPrefix());
        assertEquals("Advanced", details.subgroupName());
        assertEquals("advanced_", details.subgroupPrefix());
        assertEquals(details, descriptor.details());
    }

    @Test
    void propertiesValidateAccessIndexAndGroupingPairs() {
        assertThrows(
                IllegalArgumentException.class, () -> property("", "setValue", -1, "", "", "", ""));
        assertThrows(
                IllegalArgumentException.class, () -> property("getValue", "", -2, "", "", "", ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> property("getValue", "", -1, "", "physics_", "", ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> property("getValue", "", -1, "", "", "", "advanced_"));

        FoundryPropertyDetails namesWithoutPrefixes =
                property("getValue", "", -1, "Physics", "", "Advanced", "");
        assertEquals("Physics", namesWithoutPrefixes.groupName());
        assertEquals("Advanced", namesWithoutPrefixes.subgroupName());
    }

    @Test
    void memberKindsRejectIncoherentDetails() {
        FoundryConstantDetails constant = new FoundryConstantDetails("", 1L, false);
        FoundryPropertyDetails property = property("getValue", "setValue", -1, "", "", "", "");

        assertThrows(
                IllegalArgumentException.class,
                () -> new FoundryMemberDescriptor("method", "run", "run", "void()", constant));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FoundryMemberDescriptor("constant", "VALUE", "VALUE", "long", property));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new FoundryMemberDescriptor(
                                "constant", "VALUE", "VALUE", "long", FoundryMemberDetails.none()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new FoundryMemberDescriptor(
                                "signal", "changed", "Changed", "void()", property));
    }

    @Test
    void legacyPropertyDescriptorsRemainAdditivelyCompatible() {
        FoundryMemberDescriptor descriptor =
                new FoundryMemberDescriptor("property", "value", "value", "int");

        assertEquals(FoundryMemberDetails.none(), descriptor.details());
    }

    private static FoundryPropertyDetails property(
            String getter,
            String setter,
            int index,
            String groupName,
            String groupPrefix,
            String subgroupName,
            String subgroupPrefix) {
        return new FoundryPropertyDetails(
                getter, setter, index, groupName, groupPrefix, subgroupName, subgroupPrefix);
    }
}
