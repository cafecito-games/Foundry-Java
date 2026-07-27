package games.cafecito.foundry.annotations;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FoundryAnnotationApiTest {
    @Test
    void exposesTheExactAuthoringAnnotations() {
        assertAnnotation(
                FoundryClass.class,
                RetentionPolicy.CLASS,
                new java.lang.annotation.ElementType[] {java.lang.annotation.ElementType.TYPE});
        assertAnnotation(
                FoundryMethod.class,
                RetentionPolicy.SOURCE,
                new java.lang.annotation.ElementType[] {java.lang.annotation.ElementType.METHOD});
        assertAnnotation(
                FoundryProperty.class,
                RetentionPolicy.SOURCE,
                new java.lang.annotation.ElementType[] {java.lang.annotation.ElementType.FIELD});
        assertAnnotation(
                FoundryConstant.class,
                RetentionPolicy.SOURCE,
                new java.lang.annotation.ElementType[] {java.lang.annotation.ElementType.FIELD});
        assertAnnotation(
                FoundrySignal.class,
                RetentionPolicy.SOURCE,
                new java.lang.annotation.ElementType[] {java.lang.annotation.ElementType.TYPE});
        assertAnnotation(
                FoundryOverride.class,
                RetentionPolicy.SOURCE,
                new java.lang.annotation.ElementType[] {java.lang.annotation.ElementType.METHOD});
        assertAnnotation(
                FoundryInitialization.class,
                RetentionPolicy.CLASS,
                new java.lang.annotation.ElementType[] {java.lang.annotation.ElementType.TYPE});
    }

    @Test
    void exposesStableAnnotationMembersAndDefaults() {
        Map<String, Object> classDefaults = new HashMap<>();
        classDefaults.put("base", null);
        classDefaults.put("name", "");
        assertMembers(FoundryClass.class, classDefaults);
        assertEquals(Class.class, method(FoundryClass.class, "base").getReturnType());
        assertMembers(FoundryMethod.class, Map.of("name", ""));
        assertMembers(
                FoundryProperty.class,
                Map.of(
                        "getter",
                        "",
                        "groupName",
                        "",
                        "groupPrefix",
                        "",
                        "index",
                        -1,
                        "name",
                        "",
                        "setter",
                        "",
                        "subgroupName",
                        "",
                        "subgroupPrefix",
                        ""));
        assertEquals(String.class, method(FoundryProperty.class, "name").getReturnType());
        assertEquals(String.class, method(FoundryProperty.class, "getter").getReturnType());
        assertEquals(String.class, method(FoundryProperty.class, "setter").getReturnType());
        assertEquals(int.class, method(FoundryProperty.class, "index").getReturnType());
        assertEquals(String.class, method(FoundryProperty.class, "groupName").getReturnType());
        assertEquals(String.class, method(FoundryProperty.class, "groupPrefix").getReturnType());
        assertEquals(String.class, method(FoundryProperty.class, "subgroupName").getReturnType());
        assertEquals(String.class, method(FoundryProperty.class, "subgroupPrefix").getReturnType());
        assertMembers(FoundryConstant.class, Map.of("bitfield", false, "enumName", "", "name", ""));
        assertEquals(String.class, method(FoundryConstant.class, "name").getReturnType());
        assertEquals(String.class, method(FoundryConstant.class, "enumName").getReturnType());
        assertEquals(boolean.class, method(FoundryConstant.class, "bitfield").getReturnType());
        assertMembers(FoundrySignal.class, Map.of("name", ""));
        assertMembers(FoundryOverride.class, Map.of("name", ""));
        assertEquals(
                InitializationLevel.SCENE,
                method(FoundryInitialization.class, "value").getDefaultValue());
        assertEquals(Class[].class, method(FoundryInitialization.class, "after").getReturnType());
        assertArrayEquals(
                new Class<?>[0],
                (Class<?>[]) method(FoundryInitialization.class, "after").getDefaultValue());
        assertArrayEquals(
                new InitializationLevel[] {
                    InitializationLevel.CORE,
                    InitializationLevel.SERVERS,
                    InitializationLevel.SCENE,
                    InitializationLevel.EDITOR
                },
                InitializationLevel.values());
    }

    @Test
    void keepsGeneratedProvenanceOnTypesAndMethodsInClassFiles() {
        assertAnnotation(
                GeneratedByFoundry.class,
                RetentionPolicy.CLASS,
                new java.lang.annotation.ElementType[] {
                    java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.METHOD
                });
        assertAnnotation(
                FoundryVirtual.class,
                RetentionPolicy.CLASS,
                new java.lang.annotation.ElementType[] {java.lang.annotation.ElementType.METHOD});
        assertEquals(String.class, method(FoundryVirtual.class, "value").getReturnType());
        assertEquals(null, method(FoundryVirtual.class, "value").getDefaultValue());
    }

    private static void assertAnnotation(
            Class<?> type, RetentionPolicy retention, java.lang.annotation.ElementType[] targets) {
        assertEquals(retention, type.getAnnotation(Retention.class).value());
        assertArrayEquals(targets, type.getAnnotation(Target.class).value());
    }

    private static void assertMembers(Class<?> type, Map<String, Object> expectedDefaults) {
        Map<String, Method> actual =
                Arrays.stream(type.getDeclaredMethods())
                        .collect(Collectors.toMap(Method::getName, method -> method));
        assertEquals(expectedDefaults.keySet(), actual.keySet());
        expectedDefaults.forEach(
                (name, expectedDefault) ->
                        assertEquals(expectedDefault, actual.get(name).getDefaultValue(), name));
    }

    private static Method method(Class<?> type, String name) {
        try {
            return type.getDeclaredMethod(name);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
    }
}
