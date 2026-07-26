package games.cafecito.foundry.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.cafecito.foundry.types.Variant;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RuntimeInterfaceTest {
    @Test
    void freezesTheWs7ReentrantCallbackContract() {
        assertTrue(FoundryBridgeCallbacks.class.isInterface());
        assertEquals(
                Set.of(
                        "initialize(long,int):boolean",
                        "deinitialize(long,int):void",
                        "invoke(long,long,long[]):long",
                        "invalidate(long):void"),
                publicMethods(FoundryBridgeCallbacks.class));
    }

    @Test
    void freezesTheHostNeutralEngineTransportContract() {
        assertTrue(FoundryEngine.class.isInterface());
        assertEquals(
                Set.of(
                        "call(long,long,String,List):CallResult",
                        "decodeVariant(long,long):Variant",
                        "encodeVariant(long,Variant):long",
                        "instantiate(long,String):long",
                        "isObjectValid(long,long):boolean",
                        "objectType(long,long):String",
                        "release(long,long):void",
                        "reportCallbackException(long,long,Throwable):void",
                        "retain(long,long):void",
                        "singleton(long,String):long"),
                publicMethods(FoundryEngine.class));
    }

    private static Set<String> publicMethods(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(RuntimeInterfaceTest::signature)
                .collect(Collectors.toSet());
    }

    private static String signature(Method method) {
        return method.getName()
                + "("
                + Arrays.stream(method.getParameterTypes())
                        .map(RuntimeInterfaceTest::simpleName)
                        .collect(Collectors.joining(","))
                + "):"
                + simpleName(method.getReturnType());
    }

    private static String simpleName(Class<?> type) {
        if (type.isArray()) {
            return simpleName(type.componentType()) + "[]";
        }
        if (type.equals(List.class)) {
            return "List";
        }
        if (type.equals(String.class)) {
            return "String";
        }
        if (type.equals(Throwable.class)) {
            return "Throwable";
        }
        if (type.equals(Variant.class)) {
            return "Variant";
        }
        return type.getSimpleName();
    }
}
