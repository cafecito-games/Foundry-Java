package games.cafecito.foundry.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import games.cafecito.foundry.generated.GeneratedNativeDispatch;
import java.util.List;
import org.junit.jupiter.api.Test;

class FoundryNativeDispatchTest {
    @Test
    void freezesStableKindWireCodes() {
        assertEquals(1, FoundryNativeDispatch.Kind.CLASS_METHOD.wireCode());
        assertEquals(2, FoundryNativeDispatch.Kind.CLASS_PROPERTY.wireCode());
        assertEquals(3, FoundryNativeDispatch.Kind.CLASS_SIGNAL.wireCode());
        assertEquals(4, FoundryNativeDispatch.Kind.BUILTIN_METHOD.wireCode());
        assertEquals(5, FoundryNativeDispatch.Kind.BUILTIN_CONSTRUCTOR.wireCode());
        assertEquals(6, FoundryNativeDispatch.Kind.BUILTIN_OPERATOR.wireCode());
        assertEquals(7, FoundryNativeDispatch.Kind.BUILTIN_MEMBER.wireCode());
        assertEquals(8, FoundryNativeDispatch.Kind.BUILTIN_CONSTANT.wireCode());
        assertEquals(9, FoundryNativeDispatch.Kind.UTILITY_FUNCTION.wireCode());
    }

    @Test
    void copiesFormalTypesAndPreservesDefaultArgumentRange() {
        var arguments = new java.util.ArrayList<>(List.of("StringName", "bool", "Variant"));
        FoundryNativeDispatch dispatch =
                method(arguments, 1, false, FoundryNativeDispatch.Kind.CLASS_METHOD);

        arguments.clear();

        assertEquals(List.of("StringName", "bool", "Variant"), dispatch.argumentNativeTypes());
        assertEquals(1, dispatch.minimumArgumentCount());
        assertThrows(
                UnsupportedOperationException.class,
                () -> dispatch.argumentNativeTypes().add("Object"));
    }

    @Test
    void rejectsImpossibleFormalArityAndUnsignedHashValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> method(List.of("String"), 2, false, FoundryNativeDispatch.Kind.CLASS_METHOD));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new FoundryNativeDispatch(
                                "classes/Node/methods/name#1",
                                FoundryNativeDispatch.Kind.CLASS_METHOD,
                                "Node",
                                "name",
                                0x1_0000_0000L,
                                -1,
                                List.of(),
                                0,
                                "String",
                                "",
                                "",
                                -1,
                                "",
                                "",
                                -1,
                                false,
                                false));
    }

    @Test
    void validatesKindSpecificConstructorAndPropertyMetadata() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new FoundryNativeDispatch(
                                "builtin_classes/String/constructors/#0",
                                FoundryNativeDispatch.Kind.BUILTIN_CONSTRUCTOR,
                                "String",
                                "String",
                                -1,
                                -1,
                                List.of(),
                                0,
                                "String",
                                "",
                                "",
                                -1,
                                "",
                                "",
                                -1,
                                false,
                                true));

        FoundryNativeDispatch property =
                new FoundryNativeDispatch(
                        "classes/Node/properties/name",
                        FoundryNativeDispatch.Kind.CLASS_PROPERTY,
                        "Node",
                        "name",
                        -1,
                        -1,
                        List.of("StringName"),
                        0,
                        "StringName",
                        "classes/Node/methods/get_name#1",
                        "get_name",
                        1,
                        "classes/Node/methods/set_name#2",
                        "set_name",
                        2,
                        false,
                        false);

        assertEquals("get_name", property.getterNativeName());
        assertEquals("set_name", property.setterNativeName());
    }

    @Test
    void rejectsPartialAccessorBundlesAndMetadataOnWrongKinds() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new FoundryNativeDispatch(
                                "classes/Node/properties/name",
                                FoundryNativeDispatch.Kind.CLASS_PROPERTY,
                                "Node",
                                "name",
                                -1,
                                -1,
                                List.of("StringName"),
                                0,
                                "StringName",
                                "classes/Node/methods/get_name#1",
                                "",
                                1,
                                "",
                                "",
                                -1,
                                false,
                                false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new FoundryNativeDispatch(
                                "classes/Node/signals/ready",
                                FoundryNativeDispatch.Kind.CLASS_SIGNAL,
                                "Node",
                                "ready",
                                -1,
                                -1,
                                List.of(),
                                0,
                                "Signal",
                                "unexpected",
                                "unexpected",
                                1,
                                "",
                                "",
                                -1,
                                false,
                                false));
    }

    @Test
    void requiresKindSpecificSentinelsAndCallableShapes() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        bareDispatch(
                                FoundryNativeDispatch.Kind.BUILTIN_CONSTRUCTOR,
                                "AABB",
                                "AABB",
                                -1,
                                0,
                                List.of(),
                                0,
                                "AABB",
                                false,
                                false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        bareDispatch(
                                FoundryNativeDispatch.Kind.UTILITY_FUNCTION,
                                "UtilityFunctions",
                                "abs",
                                1,
                                -1,
                                List.of("Variant"),
                                1,
                                "Variant",
                                false,
                                false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        bareDispatch(
                                FoundryNativeDispatch.Kind.BUILTIN_CONSTRUCTOR,
                                "AABB",
                                "",
                                -1,
                                0,
                                List.of("Vector3", "Vector3"),
                                1,
                                "AABB",
                                true,
                                false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        bareDispatch(
                                FoundryNativeDispatch.Kind.BUILTIN_OPERATOR,
                                "Vector3",
                                "+",
                                -1,
                                -1,
                                List.of("Vector3"),
                                0,
                                "Vector3",
                                false,
                                false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        bareDispatch(
                                FoundryNativeDispatch.Kind.UTILITY_FUNCTION,
                                "",
                                "abs",
                                1,
                                -1,
                                List.of("Variant"),
                                1,
                                "Variant",
                                false,
                                true));
    }

    @Test
    void realGeneratedCatalogInitializesAcrossEverySentinelShape() {
        FoundryNativeDispatch constructor =
                GeneratedNativeDispatch.require("builtin_classes/AABB/constructors/#0");
        FoundryNativeDispatch utility =
                GeneratedNativeDispatch.require("utility_functions/abs#4776452");
        FoundryNativeDispatch voidMethod =
                GeneratedNativeDispatch.require("classes/Button/methods/set_text#83702148");
        FoundryNativeDispatch nameOnlyProperty =
                GeneratedNativeDispatch.require("classes/BitMap/properties/data");

        assertEquals(FoundryNativeDispatch.Kind.BUILTIN_CONSTRUCTOR, constructor.kind());
        assertEquals("", constructor.nativeName());
        assertEquals(FoundryNativeDispatch.Kind.UTILITY_FUNCTION, utility.kind());
        assertEquals("", utility.ownerNativeType());
        assertEquals("void", voidMethod.returnNativeType());
        assertEquals("", nameOnlyProperty.getterIdentity());
        assertEquals("_get_data", nameOnlyProperty.getterNativeName());
        assertEquals(-1, nameOnlyProperty.getterCompatibilityHash());
    }

    private static FoundryNativeDispatch method(
            List<String> arguments,
            int minimumArgumentCount,
            boolean vararg,
            FoundryNativeDispatch.Kind kind) {
        return new FoundryNativeDispatch(
                "classes/Node/methods/demo#1",
                kind,
                "Node",
                "demo",
                1,
                -1,
                arguments,
                minimumArgumentCount,
                "Variant",
                "",
                "",
                -1,
                "",
                "",
                -1,
                vararg,
                false);
    }

    private static FoundryNativeDispatch bareDispatch(
            FoundryNativeDispatch.Kind kind,
            String ownerNativeType,
            String nativeName,
            long compatibilityHash,
            int constructorIndex,
            List<String> argumentNativeTypes,
            int minimumArgumentCount,
            String returnNativeType,
            boolean vararg,
            boolean staticCall) {
        return new FoundryNativeDispatch(
                "test/dispatch",
                kind,
                ownerNativeType,
                nativeName,
                compatibilityHash,
                constructorIndex,
                argumentNativeTypes,
                minimumArgumentCount,
                returnNativeType,
                "",
                "",
                -1,
                "",
                "",
                -1,
                vararg,
                staticCall);
    }
}
