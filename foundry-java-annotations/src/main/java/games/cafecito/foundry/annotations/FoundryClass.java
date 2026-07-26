package games.cafecito.foundry.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares a Java class that is registered as a Foundry extension class. */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface FoundryClass {
    /**
     * @return the generated Foundry engine class that the extension directly extends
     */
    Class<?> base();

    /**
     * @return the exported class name, or the Java simple name when empty
     */
    String name() default "";
}
