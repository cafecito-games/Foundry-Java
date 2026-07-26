package games.cafecito.foundry.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares a nested functional interface as a Foundry signal signature. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface FoundrySignal {
    /**
     * @return the exported signal name, or the nested interface simple name when empty
     */
    String name() default "";
}
