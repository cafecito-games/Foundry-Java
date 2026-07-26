package games.cafecito.foundry.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Exposes a field through named Java accessor methods. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.FIELD)
public @interface FoundryProperty {
    /**
     * @return the exported property name, or the Java field name when empty
     */
    String name() default "";

    /**
     * @return the public zero-argument Java getter name
     */
    String getter() default "";

    /**
     * @return the public one-argument Java setter name, or empty for a read-only property
     */
    String setter() default "";
}
