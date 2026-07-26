package games.cafecito.foundry.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a direct Java implementation of a generated Foundry virtual method. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface FoundryOverride {
    /**
     * @return the Foundry virtual name, or the Java method name when empty
     */
    String name() default "";
}
