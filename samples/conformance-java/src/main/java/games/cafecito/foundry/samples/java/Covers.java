package games.cafecito.foundry.samples.java;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Binds one named conformance test to the matrix categories it demonstrates. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Covers {
    ConformanceCategory[] value();
}
