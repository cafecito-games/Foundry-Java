package games.cafecito.foundry.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.cafecito.foundry.types.Aabb;
import games.cafecito.foundry.types.Basis;
import games.cafecito.foundry.types.Color;
import games.cafecito.foundry.types.FoundryArray;
import games.cafecito.foundry.types.FoundryDictionary;
import games.cafecito.foundry.types.NodePath;
import games.cafecito.foundry.types.PackedByteArray;
import games.cafecito.foundry.types.PackedColorArray;
import games.cafecito.foundry.types.PackedFloat32Array;
import games.cafecito.foundry.types.PackedFloat64Array;
import games.cafecito.foundry.types.PackedInt32Array;
import games.cafecito.foundry.types.PackedInt64Array;
import games.cafecito.foundry.types.PackedStringArray;
import games.cafecito.foundry.types.PackedVector2Array;
import games.cafecito.foundry.types.PackedVector3Array;
import games.cafecito.foundry.types.PackedVector4Array;
import games.cafecito.foundry.types.Plane;
import games.cafecito.foundry.types.Projection;
import games.cafecito.foundry.types.Quaternion;
import games.cafecito.foundry.types.Rect2;
import games.cafecito.foundry.types.Rect2i;
import games.cafecito.foundry.types.Rid;
import games.cafecito.foundry.types.StringName;
import games.cafecito.foundry.types.Transform2D;
import games.cafecito.foundry.types.Transform3D;
import games.cafecito.foundry.types.Variant;
import games.cafecito.foundry.types.VariantCodec;
import games.cafecito.foundry.types.VariantConversionException;
import games.cafecito.foundry.types.VariantType;
import games.cafecito.foundry.types.Vector2;
import games.cafecito.foundry.types.Vector2i;
import games.cafecito.foundry.types.Vector3;
import games.cafecito.foundry.types.Vector3i;
import games.cafecito.foundry.types.Vector4;
import games.cafecito.foundry.types.Vector4i;
import java.util.EnumSet;
import java.util.Set;
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
        Vector2 vector2 = new Vector2(1, 2);
        Vector3 vector3 = new Vector3(1, 2, 3);
        Vector4 vector4 = new Vector4(1, 2, 3, 4);
        Color color = new Color(0.1, 0.2, 0.3, 1);
        FoundryArray<Variant> array = new FoundryArray<>(VariantCodec.VARIANT);
        FoundryDictionary<Variant, Variant> dictionary =
                new FoundryDictionary<>(VariantCodec.VARIANT, VariantCodec.VARIANT);
        ObjectLifecycleTest.CountingEngine engine = new ObjectLifecycleTest.CountingEngine();
        engine.valid(7);
        FoundryBindingContext context = new FoundryBindingContext(91, engine);
        TestVariantObject object =
                context.bind(
                        7,
                        ObjectOwnership.BORROWED,
                        TestVariantObject.class,
                        TestVariantObject::new);
        return Stream.of(
                Arguments.of(true, VariantType.BOOLEAN),
                Arguments.of(42L, VariantType.INTEGER),
                Arguments.of(42.5d, VariantType.FLOAT),
                Arguments.of("player", VariantType.STRING),
                Arguments.of(vector2, VariantType.VECTOR2),
                Arguments.of(new Vector2i(1, 2), VariantType.VECTOR2I),
                Arguments.of(new Rect2(vector2, vector2), VariantType.RECT2),
                Arguments.of(
                        new Rect2i(new Vector2i(1, 2), new Vector2i(3, 4)), VariantType.RECT2I),
                Arguments.of(vector3, VariantType.VECTOR3),
                Arguments.of(new Vector3i(1, 2, 3), VariantType.VECTOR3I),
                Arguments.of(new Transform2D(vector2, vector2, vector2), VariantType.TRANSFORM2D),
                Arguments.of(vector4, VariantType.VECTOR4),
                Arguments.of(new Vector4i(1, 2, 3, 4), VariantType.VECTOR4I),
                Arguments.of(new Plane(vector3, 4), VariantType.PLANE),
                Arguments.of(new Quaternion(1, 2, 3, 4), VariantType.QUATERNION),
                Arguments.of(new Aabb(vector3, vector3), VariantType.AABB),
                Arguments.of(new Basis(vector3, vector3, vector3), VariantType.BASIS),
                Arguments.of(
                        new Transform3D(new Basis(vector3, vector3, vector3), vector3),
                        VariantType.TRANSFORM3D),
                Arguments.of(
                        new Projection(vector4, vector4, vector4, vector4), VariantType.PROJECTION),
                Arguments.of(color, VariantType.COLOR),
                Arguments.of(new StringName("health_changed"), VariantType.STRING_NAME),
                Arguments.of(new NodePath("/root/Player"), VariantType.NODE_PATH),
                Arguments.of(new Rid(17), VariantType.RID),
                Arguments.of(object, VariantType.OBJECT),
                Arguments.of(
                        FoundryCallable.variadic(arguments -> Variant.nil()), VariantType.CALLABLE),
                Arguments.of(new FoundrySignal(), VariantType.SIGNAL),
                Arguments.of(dictionary, VariantType.DICTIONARY),
                Arguments.of(array, VariantType.ARRAY),
                Arguments.of(new PackedByteArray(new byte[] {1}), VariantType.PACKED_BYTE_ARRAY),
                Arguments.of(new PackedInt32Array(new int[] {1}), VariantType.PACKED_INT32_ARRAY),
                Arguments.of(new PackedInt64Array(new long[] {1}), VariantType.PACKED_INT64_ARRAY),
                Arguments.of(
                        new PackedFloat32Array(new float[] {1}), VariantType.PACKED_FLOAT32_ARRAY),
                Arguments.of(
                        new PackedFloat64Array(new double[] {1}), VariantType.PACKED_FLOAT64_ARRAY),
                Arguments.of(
                        new PackedStringArray(new String[] {"one"}),
                        VariantType.PACKED_STRING_ARRAY),
                Arguments.of(
                        new PackedVector2Array(new Vector2[] {vector2}),
                        VariantType.PACKED_VECTOR2_ARRAY),
                Arguments.of(
                        new PackedVector3Array(new Vector3[] {vector3}),
                        VariantType.PACKED_VECTOR3_ARRAY),
                Arguments.of(
                        new PackedColorArray(new Color[] {color}), VariantType.PACKED_COLOR_ARRAY),
                Arguments.of(
                        new PackedVector4Array(new Vector4[] {vector4}),
                        VariantType.PACKED_VECTOR4_ARRAY));
    }

    @Test
    void everyAdvertisedVariantTypeHasConstructionAccessAndCodecPaths() {
        Set<VariantType> covered =
                supportedValues()
                        .map(arguments -> (VariantType) arguments.get()[1])
                        .collect(
                                java.util.stream.Collectors.toCollection(
                                        () -> EnumSet.of(VariantType.NIL)));
        assertEquals(EnumSet.allOf(VariantType.class), covered);

        supportedValues()
                .forEach(
                        arguments -> {
                            Object input = arguments.get()[0];
                            VariantType type = (VariantType) arguments.get()[1];
                            Variant value = Variant.of(input);
                            assertEquals(input, value.as(type));
                            VariantCodec<Object> codec = VariantCodec.forType(type);
                            assertEquals(input, codec.decode(codec.encode(input)));
                        });
        assertSame(Variant.nil(), Variant.nil().copy());
    }

    @Test
    void objectCallableAndSignalFactoriesRejectNullInsteadOfForgingNil() {
        assertThrows(NullPointerException.class, () -> Variant.ofObject(null));
        assertThrows(NullPointerException.class, () -> Variant.ofCallable(null));
        assertThrows(NullPointerException.class, () -> Variant.ofSignal(null));
    }

    @Test
    void variantsPreserveNativeCallableAndSignalBridgeIdentity() {
        FoundryCallable callable =
                FoundryCallable.nativeBacked(
                        11,
                        51,
                        -1,
                        new FoundryCallable.NativeBackend() {
                            @Override
                            public Variant invoke(
                                    long contextHandle,
                                    long bridgeHandle,
                                    java.util.List<Variant> arguments) {
                                return Variant.nil();
                            }

                            @Override
                            public void release(long contextHandle, long bridgeHandle) {}
                        });
        FoundrySignal signal =
                FoundrySignal.nativeBacked(
                        11,
                        52,
                        new FoundrySignal.NativeBackend() {
                            @Override
                            public long connect(
                                    long contextHandle,
                                    long signalHandle,
                                    FoundryCallable listener) {
                                return 1;
                            }

                            @Override
                            public void disconnect(
                                    long contextHandle,
                                    long signalHandle,
                                    long connectionHandle) {}

                            @Override
                            public void emit(
                                    long contextHandle,
                                    long signalHandle,
                                    java.util.List<Variant> arguments) {}

                            @Override
                            public void release(long contextHandle, long signalHandle) {}
                        });

        assertEquals(51, Variant.ofCallable(callable).asCallable().nativeBridgeHandle());
        assertEquals(52, Variant.ofSignal(signal).asSignal().nativeBridgeHandle());
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
    void normalizesSignedZeroAndNanRecursivelyAcrossCompositeAndPackedValues() {
        Variant compositeA =
                Variant.of(
                        new Transform3D(
                                new Basis(
                                        new Vector3(-0.0d, Double.NaN, 1.0d),
                                        new Vector3(2.0d, 3.0d, 4.0d),
                                        new Vector3(5.0d, 6.0d, 7.0d)),
                                new Vector3(8.0d, 9.0d, -0.0d)));
        Variant compositeB =
                Variant.of(
                        new Transform3D(
                                new Basis(
                                        new Vector3(
                                                0.0d,
                                                Double.longBitsToDouble(0x7ff8000000000001L),
                                                1.0d),
                                        new Vector3(2.0d, 3.0d, 4.0d),
                                        new Vector3(5.0d, 6.0d, 7.0d)),
                                new Vector3(8.0d, 9.0d, 0.0d)));
        Variant packedA =
                Variant.of(new PackedFloat64Array(new double[] {-0.0d, Double.NaN, 4.0d}));
        Variant packedB =
                Variant.of(
                        new PackedFloat64Array(
                                new double[] {
                                    0.0d, Double.longBitsToDouble(0x7ff8000000000001L), 4.0d
                                }));

        assertEquals(compositeA, compositeB);
        assertEquals(compositeA.hashCode(), compositeB.hashCode());
        assertEquals(packedA, packedB);
        assertEquals(packedA.hashCode(), packedB.hashCode());
        assertEquals(
                Variant.of(new PackedFloat32Array(new float[] {-0.0f, Float.NaN})),
                Variant.of(
                        new PackedFloat32Array(
                                new float[] {0.0f, Float.intBitsToFloat(0x7fc00001)})));
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

    static final class TestVariantObject extends FoundryObject {
        TestVariantObject(FoundryBindingContext context, ObjectLease lease) {
            super(context, lease);
        }
    }
}
