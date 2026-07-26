package games.cafecito.foundry.types;

/** Raised when Java code requests a value using a different Foundry Variant type. */
public final class VariantConversionException extends IllegalStateException {
    public VariantConversionException(VariantType source, VariantType target) {
        super(source + " Variant cannot be converted to " + target);
    }
}
