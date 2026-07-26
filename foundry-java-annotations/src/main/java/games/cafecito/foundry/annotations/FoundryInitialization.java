package games.cafecito.foundry.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Defines an extension class's registration level and deterministic dependencies. */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface FoundryInitialization {
    /**
     * @return the earliest Foundry initialization level at which the class is registered
     */
    InitializationLevel value() default InitializationLevel.SCENE;

    /**
     * @return extension classes that must be registered before this class
     */
    Class<?>[] after() default {};
}
