package games.cafecito.foundry.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a Java declaration that is part of the public Foundry extension ABI. */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface PublicFoundryAbi {}
