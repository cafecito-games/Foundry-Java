package games.cafecito.foundry.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Exposes an instance method through the Foundry extension ABI. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface FoundryMethod {
    /**
     * @return the exported method name, or the Java method name when empty
     */
    String name() default "";
}
