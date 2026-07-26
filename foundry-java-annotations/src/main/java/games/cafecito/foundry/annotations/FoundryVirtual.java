package games.cafecito.foundry.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Preserves the original Foundry identity of a generated Java virtual method. */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface FoundryVirtual {
    /**
     * @return the original Foundry virtual method name
     */
    String value();
}
