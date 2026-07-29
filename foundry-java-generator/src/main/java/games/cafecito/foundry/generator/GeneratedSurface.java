package games.cafecito.foundry.generator;

import games.cafecito.foundry.api.model.ApiInputException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The externally visible generated Java surface: every generated type together with every public or
 * protected member it declares.
 *
 * <p>Protected members belong to the surface because the generated class hierarchy is extensible;
 * generated virtual engine methods are protected overrides.
 */
public final class GeneratedSurface {
    private static final String GENERATED_PACKAGE = "games.cafecito.foundry.generated";
    private static final String GENERATED_PACKAGE_PATH = "games/cafecito/foundry/generated";

    private final Set<JavaMember> members;

    private GeneratedSurface(Collection<JavaMember> members) {
        this.members = Collections.unmodifiableSet(new TreeSet<>(members));
    }

    /** Returns a surface over the given members. */
    public static GeneratedSurface of(Collection<JavaMember> members) {
        return new GeneratedSurface(members);
    }

    /**
     * Reads the surface from compiled classes. The class path of the running process must resolve
     * every type the generated declarations reference.
     */
    public static GeneratedSurface fromCompiledClasses(Path classesDirectory) {
        List<String> binaryNames = binaryNames(classesDirectory);
        List<URL> urls = new ArrayList<>();
        try {
            urls.add(classesDirectory.toUri().toURL());
        } catch (MalformedURLException exception) {
            throw new ApiInputException("Could not resolve the generated class path.", exception);
        }
        List<JavaMember> collected = new ArrayList<>();
        try (URLClassLoader loader =
                new URLClassLoader(
                        urls.toArray(URL[]::new), GeneratedSurface.class.getClassLoader())) {
            for (String binaryName : binaryNames) {
                collect(loader, binaryName, collected);
            }
        } catch (IOException exception) {
            throw new ApiInputException(
                    "Could not read compiled generated classes in " + classesDirectory + ".",
                    exception);
        }
        return new GeneratedSurface(collected);
    }

    /** Returns every generated type and member ordered by owner, name, and erased signature. */
    public Set<JavaMember> members() {
        return members;
    }

    /** Returns whether {@code member} exists in the generated surface. */
    public boolean contains(JavaMember member) {
        return members.contains(member);
    }

    private static List<String> binaryNames(Path classesDirectory) {
        Path generatedRoot = classesDirectory.resolve(GENERATED_PACKAGE_PATH);
        if (!Files.isDirectory(generatedRoot)) {
            throw new ApiInputException(
                    "Compiled generated classes are absent: " + generatedRoot + ".");
        }
        List<String> binaryNames = new ArrayList<>();
        try (Stream<Path> files = Files.walk(generatedRoot)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String relative = classesDirectory.relativize(file).toString().replace('\\', '/');
                if (!relative.endsWith(".class")) {
                    continue;
                }
                binaryNames.add(
                        relative.substring(0, relative.length() - ".class".length())
                                .replace('/', '.'));
            }
        } catch (IOException exception) {
            throw new ApiInputException(
                    "Could not walk compiled generated classes in " + generatedRoot + ".",
                    exception);
        }
        Collections.sort(binaryNames);
        return List.copyOf(binaryNames);
    }

    private static void collect(ClassLoader loader, String binaryName, List<JavaMember> collected) {
        Class<?> type;
        try {
            type = Class.forName(binaryName, false, loader);
        } catch (ClassNotFoundException | LinkageError error) {
            throw new ApiInputException(
                    "Could not load generated class " + Diagnostics.escape(binaryName) + ".",
                    error instanceof Exception exception ? exception : new Exception(error));
        }
        if (!isVisible(type.getModifiers())) {
            return;
        }
        String owner = canonicalName(type);
        collected.add(JavaMember.ofType(owner));
        try {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic()
                        || method.isBridge()
                        || !isVisible(method.getModifiers())) {
                    continue;
                }
                List<String> erasedParameters = canonicalNames(method.getParameterTypes());
                String erasedReturn = canonicalName(method.getReturnType());
                collected.add(
                        JavaMember.ofMethod(
                                owner, method.getName(), erasedParameters, erasedReturn));
                List<String> declaredParameters = declaredNames(method.getGenericParameterTypes());
                String declaredReturn = declaredName(method.getGenericReturnType());
                if (!declaredParameters.equals(erasedParameters)
                        || !declaredReturn.equals(erasedReturn)) {
                    collected.add(
                            JavaMember.ofMethod(
                                    owner,
                                    method.getName() + JavaMember.DECLARED_VIEW_SUFFIX,
                                    declaredParameters,
                                    declaredReturn));
                }
            }
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic() || !isVisible(field.getModifiers())) {
                    continue;
                }
                String erasedFieldType = canonicalName(field.getType());
                collected.add(JavaMember.ofField(owner, field.getName(), erasedFieldType));
                String declaredFieldType = declaredName(field.getGenericType());
                if (!declaredFieldType.equals(erasedFieldType)) {
                    collected.add(
                            JavaMember.ofField(
                                    owner,
                                    field.getName() + JavaMember.DECLARED_VIEW_SUFFIX,
                                    declaredFieldType));
                }
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (constructor.isSynthetic() || !isVisible(constructor.getModifiers())) {
                    continue;
                }
                List<String> erasedConstructorParameters =
                        canonicalNames(constructor.getParameterTypes());
                collected.add(JavaMember.ofConstructor(owner, erasedConstructorParameters));
                List<String> declaredConstructorParameters =
                        declaredNames(constructor.getGenericParameterTypes());
                if (!declaredConstructorParameters.equals(erasedConstructorParameters)) {
                    collected.add(
                            new JavaMember(
                                    owner,
                                    JavaMember.CONSTRUCTOR_MEMBER_NAME
                                            + JavaMember.DECLARED_VIEW_SUFFIX,
                                    "("
                                            + String.join(",", declaredConstructorParameters)
                                            + ")void"));
                }
            }
        } catch (LinkageError error) {
            throw new ApiInputException(
                    "Could not inspect generated class " + Diagnostics.escape(binaryName) + ".",
                    new Exception(error));
        }
    }

    private static boolean isVisible(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static List<String> canonicalNames(Class<?>[] types) {
        List<String> names = new ArrayList<>(types.length);
        for (Class<?> type : types) {
            names.add(canonicalName(type));
        }
        return names;
    }

    private static List<String> declaredNames(Type[] types) {
        List<String> names = new ArrayList<>(types.length);
        for (Type type : types) {
            names.add(declaredName(type));
        }
        return names;
    }

    /**
     * Renders a declared type the way a generated declaration writes it: canonical names with
     * nested-type separators, keeping every type argument.
     */
    private static String declaredName(Type type) {
        return type instanceof Class<?> raw
                ? canonicalName(raw)
                : type.getTypeName().replace('$', '.');
    }

    private static String canonicalName(Class<?> type) {
        String canonicalName = type.getCanonicalName();
        if (canonicalName == null) {
            throw new ApiInputException(
                    "Generated surface references an unnameable type " + type.getName() + ".");
        }
        return canonicalName;
    }

    /** Returns the generated package every surface member belongs to. */
    public static String generatedPackage() {
        return GENERATED_PACKAGE;
    }
}
