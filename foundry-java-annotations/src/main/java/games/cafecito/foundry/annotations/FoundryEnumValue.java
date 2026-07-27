package games.cafecito.foundry.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Assigns the explicit Foundry integer representation of a Java enum constant. */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface FoundryEnumValue {
    /**
     * @return the signed integer value used by the Foundry Variant ABI
     */
    long value();
}
