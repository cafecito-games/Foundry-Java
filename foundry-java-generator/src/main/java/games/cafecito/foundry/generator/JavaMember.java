package games.cafecito.foundry.generator;

import games.cafecito.foundry.api.model.ApiInputException;
import java.util.List;

/**
 * One realized element of the generated Java surface: its fully qualified owner, its member name,
 * and its erased signature.
 *
 * <p>Erased signatures use canonical Java type names with every type argument removed, so a
 * generated declaration and the compiled member it produces render identically. Four member shapes
 * exist:
 *
 * <ul>
 *   <li>a type declaration, named {@value #TYPE_MEMBER_NAME} with its own canonical name as the
 *       signature;
 *   <li>a constructor, named {@value #CONSTRUCTOR_MEMBER_NAME};
 *   <li>a method, whose signature is {@code (parameter,parameter)returnType};
 *   <li>a field, whose signature is its erased type.
 * </ul>
 */
public record JavaMember(String owner, String name, String erasedSignature)
        implements Comparable<JavaMember> {
    /** Member name of a realized type declaration. */
    public static final String TYPE_MEMBER_NAME = "<type>";

    /** Member name of a realized constructor. */
    public static final String CONSTRUCTOR_MEMBER_NAME = "<init>";

    /**
     * Suffix marking the declared view of a member whose declaration carries type arguments.
     * Erasure drops those arguments, so a member such as a typed signal accessor contributes both
     * its erased member and its declared member; without the declared view a wrong type argument
     * would change the public API without changing any erased signature.
     */
    public static final String DECLARED_VIEW_SUFFIX = "<>";

    public JavaMember {
        requireText(owner, "owner");
        requireText(name, "member name");
        requireText(erasedSignature, "erased signature");
    }

    /** Returns the realized declaration of the type {@code canonicalName}. */
    public static JavaMember ofType(String canonicalName) {
        return new JavaMember(canonicalName, TYPE_MEMBER_NAME, canonicalName);
    }

    /** Returns a realized method. */
    public static JavaMember ofMethod(
            String owner, String name, List<String> parameterTypes, String returnType) {
        return new JavaMember(owner, name, signature(parameterTypes) + returnType);
    }

    /** Returns a realized constructor. */
    public static JavaMember ofConstructor(String owner, List<String> parameterTypes) {
        return new JavaMember(owner, CONSTRUCTOR_MEMBER_NAME, signature(parameterTypes) + "void");
    }

    /** Returns a realized field. */
    public static JavaMember ofField(String owner, String name, String type) {
        return new JavaMember(owner, name, type);
    }

    /** Returns whether this member records the declared, type-argument-carrying view. */
    public boolean isDeclaredView() {
        return name.endsWith(DECLARED_VIEW_SUFFIX);
    }

    /** Returns this member name with the declared-view suffix removed. */
    public String erasedViewName() {
        return isDeclaredView()
                ? name.substring(0, name.length() - DECLARED_VIEW_SUFFIX.length())
                : name;
    }

    /** Returns whether this member is a type declaration rather than a member of a type. */
    public boolean isType() {
        return name.equals(TYPE_MEMBER_NAME);
    }

    /**
     * Returns the canonical name of the outermost type that declares this member. Generated package
     * segments are lower case and generated type names start upper case, so the first upper-case
     * segment names the outermost type.
     */
    public String topLevelOwner() {
        String[] segments = owner.split("\\.", -1);
        StringBuilder result = new StringBuilder();
        for (String segment : segments) {
            if (!result.isEmpty()) {
                result.append('.');
            }
            result.append(segment);
            if (!segment.isEmpty() && Character.isUpperCase(segment.charAt(0))) {
                break;
            }
        }
        return result.toString();
    }

    /** Renders this member as one stable line fragment. */
    public String render() {
        return owner + '#' + name + ':' + erasedSignature;
    }

    /** Parses the rendering produced by {@link #render()}. */
    public static JavaMember parse(String text) {
        int nameStart = text.indexOf('#');
        int signatureStart = text.indexOf(':', nameStart + 1);
        if (nameStart < 0 || signatureStart < 0) {
            throw new ApiInputException(
                    "Malformed realized Java member: " + Diagnostics.escape(text) + ".");
        }
        return new JavaMember(
                text.substring(0, nameStart),
                text.substring(nameStart + 1, signatureStart),
                text.substring(signatureStart + 1));
    }

    @Override
    public int compareTo(JavaMember other) {
        int byOwner = owner.compareTo(other.owner);
        if (byOwner != 0) {
            return byOwner;
        }
        int byName = name.compareTo(other.name);
        return byName != 0 ? byName : erasedSignature.compareTo(other.erasedSignature);
    }

    private static String signature(List<String> parameterTypes) {
        return "(" + String.join(",", parameterTypes) + ")";
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiInputException("Realized Java member requires a " + field + ".");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)
                || value.indexOf('#') >= 0
                || value.indexOf(';') >= 0
                || value.indexOf('\t') >= 0) {
            throw new ApiInputException(
                    "Realized Java member "
                            + field
                            + " is not renderable: "
                            + Diagnostics.escape(value)
                            + ".");
        }
    }
}
