package games.cafecito.foundry.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Exposes a compile-time integral field as a Foundry integer constant. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.FIELD)
public @interface FoundryConstant {
    /**
     * @return the exported constant name, or the Java field name when empty
     */
    String name() default "";

    /**
     * @return the Foundry enum or bitfield group name, or no group when empty
     */
    String enumName() default "";

    /**
     * @return whether the constant belongs to a bitfield group
     */
    boolean bitfield() default false;
}
