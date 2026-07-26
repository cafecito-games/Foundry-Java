package games.cafecito.foundry.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.cafecito.foundry.types.Color;
import games.cafecito.foundry.types.NodePath;
import games.cafecito.foundry.types.Rid;
import games.cafecito.foundry.types.StringName;
import games.cafecito.foundry.types.Variant;
import games.cafecito.foundry.types.VariantConversionException;
import games.cafecito.foundry.types.VariantType;
import games.cafecito.foundry.types.Vector2;
import games.cafecito.foundry.types.Vector3;
import games.cafecito.foundry.types.Vector4;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class VariantTest {
    @Test
    void usesOneExplicitNilValue() {
        assertSame(Variant.nil(), Variant.nil());
        assertSame(Variant.nil(), Variant.of(null));
        assertEquals(VariantType.NIL, Variant.nil().type());
        assertTrue(Variant.nil().isNil());
    }

    @ParameterizedTest
    @MethodSource("supportedValues")
    void preservesTheExactFoundryType(Object input, VariantType expectedType) {
        Variant value = Variant.of(input);

        assertEquals(expectedType, value.type());
        assertEquals(input, value.value());
    }

    static Stream<Arguments> supportedValues() {
        return Stream.of(
                Arguments.of(true, VariantType.BOOLEAN),
                Arguments.of(42L, VariantType.INTEGER),
                Arguments.of(42.5d, VariantType.FLOAT),
                Arguments.of("player", VariantType.STRING),
                Arguments.of(new Vector2(1, 2), VariantType.VECTOR2),
                Arguments.of(new Vector3(1, 2, 3), VariantType.VECTOR3),
                Arguments.of(new Vector4(1, 2, 3, 4), VariantType.VECTOR4),
                Arguments.of(new Color(0.1, 0.2, 0.3, 1), VariantType.COLOR),
                Arguments.of(new StringName("health_changed"), VariantType.STRING_NAME),
                Arguments.of(new NodePath("/root/Player"), VariantType.NODE_PATH),
                Arguments.of(new Rid(17), VariantType.RID));
    }

    @Test
    void equalityIsTypeStrict() {
        assertNotEquals(Variant.of(1L), Variant.of(1.0d));
        assertNotEquals(Variant.of(true), Variant.of(1L));
        assertNotEquals(Variant.of("player"), Variant.of(new StringName("player")));
    }

    @Test
    void normalizesFoundryFloatingPointEqualityAndHashing() {
        Variant firstNan = Variant.of(Double.NaN);
        Variant secondNan = Variant.of(Double.longBitsToDouble(0x7ff8000000000001L));
        Variant positiveZero = Variant.of(0.0d);
        Variant negativeZero = Variant.of(-0.0d);

        assertEquals(firstNan, secondNan);
        assertEquals(firstNan.hashCode(), secondNan.hashCode());
        assertEquals(positiveZero, negativeZero);
        assertEquals(positiveZero.hashCode(), negativeZero.hashCode());
    }

    @Test
    void performsOnlyStrictLosslessConversions() {
        assertEquals(true, Variant.of(true).asBoolean());
        assertEquals(42L, Variant.of(42L).asLong());
        assertEquals(42, Variant.of(42L).asInt());
        assertEquals(42.5d, Variant.of(42.5d).asDouble());
        assertEquals("player", Variant.of("player").asString());
        assertEquals(new Vector2(1, 2), Variant.of(new Vector2(1, 2)).asVector2());
        assertEquals(new Vector3(1, 2, 3), Variant.of(new Vector3(1, 2, 3)).asVector3());
        assertEquals(new Vector4(1, 2, 3, 4), Variant.of(new Vector4(1, 2, 3, 4)).asVector4());
        assertEquals(
                new Color(0.1, 0.2, 0.3, 1), Variant.of(new Color(0.1, 0.2, 0.3, 1)).asColor());
        assertEquals(new StringName("player"), Variant.of(new StringName("player")).asStringName());
        assertEquals(
                new NodePath("/root/Player"),
                Variant.of(new NodePath("/root/Player")).asNodePath());
        assertEquals(new Rid(17), Variant.of(new Rid(17)).asRid());

        assertThrows(ArithmeticException.class, () -> Variant.of(Long.MAX_VALUE).asInt());
        assertThrows(ArithmeticException.class, () -> Variant.of(16_777_217.0d).asFloat());
    }

    @Test
    void rejectsCrossTypeConversionsWithActionableDetails() {
        VariantConversionException error =
                assertThrows(VariantConversionException.class, () -> Variant.of("1").asLong());

        assertTrue(error.getMessage().contains("STRING"));
        assertTrue(error.getMessage().contains("INTEGER"));
        assertThrows(VariantConversionException.class, () -> Variant.of(1L).asDouble());
        assertThrows(VariantConversionException.class, () -> Variant.of(1.0d).asLong());
    }

    @Test
    void rejectsUnsupportedJavaValues() {
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> Variant.of(new Object()));

        assertTrue(error.getMessage().contains(Object.class.getName()));
    }

    @Test
    void foundationalValueObjectsRejectInvalidState() {
        assertThrows(NullPointerException.class, () -> new StringName(null));
        assertThrows(NullPointerException.class, () -> new NodePath(null));
        assertThrows(IllegalArgumentException.class, () -> new Rid(-1));
        assertEquals(0L, new Rid(0).id());
    }
}
