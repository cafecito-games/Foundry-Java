package games.cafecito.foundry.processor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

final class ExtensionValidator {
    static final String CLASS = "games.cafecito.foundry.annotations.FoundryClass";
    static final String METHOD = "games.cafecito.foundry.annotations.FoundryMethod";
    static final String PROPERTY = "games.cafecito.foundry.annotations.FoundryProperty";
    static final String SIGNAL = "games.cafecito.foundry.annotations.FoundrySignal";
    static final String OVERRIDE = "games.cafecito.foundry.annotations.FoundryOverride";
    static final String INITIALIZATION = "games.cafecito.foundry.annotations.FoundryInitialization";
    static final String GENERATED = "games.cafecito.foundry.annotations.GeneratedByFoundry";

    private final Types types;
    private final Elements elements;
    private final Messager messager;
    private int errorCount;

    ExtensionValidator(ProcessingEnvironment processingEnvironment) {
        types = processingEnvironment.getTypeUtils();
        elements = processingEnvironment.getElementUtils();
        messager = processingEnvironment.getMessager();
    }

    int errorCount() {
        return errorCount;
    }

    Optional<ExtensionModel> validate(TypeElement extension) {
        AnnotationMirror classAnnotation = annotation(extension, CLASS).orElseThrow();
        if (extension.getKind() != ElementKind.CLASS) {
            error(extension, "extension declaration must be a class");
            return Optional.empty();
        }
        if (extension.getEnclosingElement().getKind() != ElementKind.PACKAGE) {
            error(extension, "extension class must be top-level");
        }
        if (elements.getPackageOf(extension).isUnnamed()) {
            error(extension, "extension class must be declared in a named package");
        }
        if (!extension.getModifiers().contains(Modifier.PUBLIC)) {
            error(extension, "extension class must be public");
        }
        if (!extension.getModifiers().contains(Modifier.FINAL)) {
            error(extension, "extension class must be final");
        }
        if (extension.getModifiers().contains(Modifier.ABSTRACT)) {
            error(extension, "extension class cannot be abstract");
        }

        AnnotationValue baseValue = value(classAnnotation, "base");
        TypeMirror base = (TypeMirror) baseValue.getValue();
        Element baseElement = types.asElement(base);
        if (!(baseElement instanceof TypeElement baseType)
                || annotation(baseType, GENERATED).isEmpty()) {
            error(
                    extension,
                    classAnnotation,
                    baseValue,
                    "extension base must be a generated Foundry engine class");
        }
        if (!types.isSameType(types.erasure(extension.getSuperclass()), types.erasure(base))) {
            error(
                    extension,
                    classAnnotation,
                    baseValue,
                    "extension class must directly extend declared base " + base);
        }
        validateConstructor(extension);

        List<ExtensionModel.MethodModel> methods = new ArrayList<>();
        List<ExtensionModel.MethodModel> overrides = new ArrayList<>();
        List<ExtensionModel.PropertyModel> properties = new ArrayList<>();
        List<ExtensionModel.SignalModel> signals = new ArrayList<>();
        Map<String, Element> exportedNames = new LinkedHashMap<>();
        Map<String, VariableElement> propertyAccessors = new LinkedHashMap<>();

        for (Element member : extension.getEnclosedElements()) {
            annotation(member, METHOD)
                    .ifPresent(
                            mirror -> {
                                ExecutableElement method = (ExecutableElement) member;
                                String exported =
                                        exportedName(
                                                method, mirror, method.getSimpleName().toString());
                                validateCallable(method, "exported method");
                                validateMethodTypes(method);
                                checkDuplicate(exportedNames, exported, method);
                                methods.add(methodModel(method, exported));
                            });
            annotation(member, OVERRIDE)
                    .ifPresent(
                            mirror -> {
                                ExecutableElement method = (ExecutableElement) member;
                                String exported =
                                        exportedName(
                                                method, mirror, method.getSimpleName().toString());
                                validateCallable(method, "Foundry override");
                                validateMethodTypes(method);
                                if (!matchesBaseVirtual(base, method, exported)) {
                                    error(
                                            method,
                                            "Foundry override "
                                                    + exported
                                                    + " does not match a generated Foundry virtual method on "
                                                    + base);
                                }
                                checkDuplicate(exportedNames, exported, method);
                                overrides.add(methodModel(method, exported));
                            });
            annotation(member, PROPERTY)
                    .ifPresent(
                            mirror -> {
                                VariableElement field = (VariableElement) member;
                                String exported =
                                        exportedName(
                                                field, mirror, field.getSimpleName().toString());
                                validateSupported(field.asType(), field);
                                validateProperty(extension, field, mirror, propertyAccessors);
                                checkDuplicate(exportedNames, exported, field);
                                properties.add(
                                        new ExtensionModel.PropertyModel(
                                                field.getSimpleName().toString(),
                                                exported,
                                                field.asType().toString(),
                                                stringValue(mirror, "getter"),
                                                stringValue(mirror, "setter")));
                            });
            annotation(member, SIGNAL)
                    .ifPresent(
                            mirror -> {
                                String exported =
                                        exportedName(
                                                member, mirror, member.getSimpleName().toString());
                                Optional<ExecutableElement> signalMethod = validateSignal(member);
                                checkDuplicate(exportedNames, exported, member);
                                signals.add(
                                        signalModel(
                                                (TypeElement) member,
                                                exported,
                                                signalMethod.orElse(null)));
                            });
        }

        String initializationLevel = "SCENE";
        List<String> dependencies = List.of();
        Optional<AnnotationMirror> initialization = annotation(extension, INITIALIZATION);
        if (initialization.isPresent()) {
            initializationLevel =
                    value(initialization.get(), "value")
                            .getValue()
                            .toString()
                            .substring(
                                    value(initialization.get(), "value")
                                                    .getValue()
                                                    .toString()
                                                    .lastIndexOf('.')
                                            + 1);
            dependencies = initializationDependencies(extension, initialization.get());
        }

        String qualifiedName = extension.getQualifiedName().toString();
        return Optional.of(
                new ExtensionModel(
                        qualifiedName,
                        elements.getPackageOf(extension).getQualifiedName().toString(),
                        extension.getSimpleName().toString(),
                        exportedName(
                                extension, classAnnotation, extension.getSimpleName().toString()),
                        base.toString(),
                        initializationLevel,
                        dependencies,
                        methods,
                        properties,
                        signals,
                        overrides));
    }

    void validateAnnotationPlacement(RoundEnvironment roundEnvironment) {
        for (String annotationName : List.of(METHOD, PROPERTY, SIGNAL, OVERRIDE, INITIALIZATION)) {
            TypeElement annotationType = elements.getTypeElement(annotationName);
            if (annotationType == null) {
                continue;
            }
            for (Element annotated : roundEnvironment.getElementsAnnotatedWith(annotationType)) {
                Element extension =
                        annotationName.equals(INITIALIZATION)
                                ? annotated
                                : annotated.getEnclosingElement();
                if (annotation(extension, CLASS).isEmpty()) {
                    error(
                            annotated,
                            "@"
                                    + annotationType.getSimpleName()
                                    + " must be enclosed by a @FoundryClass");
                }
            }
        }
    }

    void validateCycles(
            Map<String, ExtensionModel> models, Map<String, TypeElement> sourceElements) {
        Map<String, List<String>> graph = new HashMap<>();
        models.forEach(
                (name, model) ->
                        graph.put(
                                name,
                                model.initializationDependencies().stream()
                                        .filter(models::containsKey)
                                        .toList()));
        Set<String> cyclic = new HashSet<>();
        for (String name : graph.keySet()) {
            findCycles(name, graph, new ArrayDeque<>(), new HashSet<>(), cyclic);
        }
        cyclic.stream()
                .sorted()
                .forEach(
                        name -> {
                            TypeElement element = sourceElements.get(name);
                            AnnotationMirror mirror =
                                    annotation(element, INITIALIZATION).orElseThrow();
                            error(
                                    element,
                                    mirror,
                                    "initialization dependency cycle includes " + name);
                        });
    }

    private void findCycles(
            String current,
            Map<String, List<String>> graph,
            ArrayDeque<String> path,
            Set<String> visited,
            Set<String> cyclic) {
        if (path.contains(current)) {
            boolean include = false;
            for (String node : path) {
                if (node.equals(current)) {
                    include = true;
                }
                if (include) {
                    cyclic.add(node);
                }
            }
            cyclic.add(current);
            return;
        }
        if (!visited.add(current)) {
            return;
        }
        path.addLast(current);
        for (String dependency : graph.getOrDefault(current, List.of())) {
            findCycles(dependency, graph, path, visited, cyclic);
        }
        path.removeLast();
    }

    private void validateConstructor(TypeElement extension) {
        List<ExecutableElement> constructors =
                ElementFilter.constructorsIn(extension.getEnclosedElements());
        Optional<ExecutableElement> publicZeroArgument =
                constructors.stream()
                        .filter(constructor -> constructor.getParameters().isEmpty())
                        .filter(constructor -> constructor.getModifiers().contains(Modifier.PUBLIC))
                        .findFirst();
        if (!constructors.isEmpty() && publicZeroArgument.isEmpty()) {
            error(extension, "extension class must provide a public zero-argument constructor");
        }
        publicZeroArgument
                .filter(this::declaresCheckedException)
                .ifPresent(
                        constructor ->
                                error(
                                        constructor,
                                        "extension constructor cannot declare checked exceptions"));
    }

    private void validateCallable(ExecutableElement method, String label) {
        if (!method.getModifiers().contains(Modifier.PUBLIC)
                || method.getModifiers().contains(Modifier.STATIC)) {
            error(method, label + " must be a public instance method");
        }
        if (!method.getTypeParameters().isEmpty()) {
            error(method, label + " cannot declare type parameters");
        }
        if (declaresCheckedException(method)) {
            error(method, label + " cannot declare checked exceptions");
        }
    }

    private boolean declaresCheckedException(ExecutableElement executable) {
        TypeElement runtimeException = elements.getTypeElement("java.lang.RuntimeException");
        TypeElement error = elements.getTypeElement("java.lang.Error");
        return executable.getThrownTypes().stream()
                .anyMatch(
                        thrown ->
                                (runtimeException == null
                                                || !types.isSubtype(
                                                        thrown, runtimeException.asType()))
                                        && (error == null
                                                || !types.isSubtype(thrown, error.asType())));
    }

    private void validateMethodTypes(ExecutableElement method) {
        validateSupported(method.getReturnType(), method, "unsupported Foundry return type ");
        method.getParameters()
                .forEach(
                        parameter ->
                                validateSupported(
                                        parameter.asType(),
                                        parameter,
                                        "unsupported Foundry parameter type "));
    }

    private void validateSupported(TypeMirror type, Element source) {
        validateSupported(type, source, "unsupported Foundry type ");
    }

    private void validateSupported(TypeMirror type, Element source, String messagePrefix) {
        if (type.getKind().isPrimitive() || type.getKind() == TypeKind.VOID) {
            return;
        }
        if (type.getKind() == TypeKind.DECLARED) {
            TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
            String name = element.getQualifiedName().toString();
            if (name.equals("java.lang.String")
                    || element.getKind() == ElementKind.ENUM
                    || name.startsWith("games.cafecito.foundry.api.")
                    || name.startsWith("games.cafecito.foundry.types.")
                    || annotation(element, CLASS).isPresent()) {
                return;
            }
        }
        error(source, messagePrefix + type);
    }

    private void validateProperty(
            TypeElement extension,
            VariableElement field,
            AnnotationMirror property,
            Map<String, VariableElement> usedAccessors) {
        String getter = stringValue(property, "getter");
        String setter = stringValue(property, "setter");
        if (getter.isEmpty()) {
            error(field, property, value(property, "getter"), "property getter must be specified");
        } else {
            checkAccessorReuse(usedAccessors, getter, field, property);
            List<ExecutableElement> candidates = methodsNamed(extension, getter);
            if (candidates.stream()
                    .noneMatch(
                            method ->
                                    isPublicInstance(method)
                                            && method.getParameters().isEmpty()
                                            && types.isSameType(
                                                    method.getReturnType(), field.asType()))) {
                error(
                        field,
                        property,
                        "property getter " + getter + " must return " + field.asType());
            }
        }
        if (!setter.isEmpty()) {
            checkAccessorReuse(usedAccessors, setter, field, property);
            List<ExecutableElement> candidates = methodsNamed(extension, setter);
            if (candidates.stream()
                    .noneMatch(
                            method ->
                                    isPublicInstance(method)
                                            && method.getReturnType().getKind() == TypeKind.VOID
                                            && method.getParameters().size() == 1
                                            && types.isSameType(
                                                    method.getParameters().get(0).asType(),
                                                    field.asType()))) {
                error(
                        field,
                        property,
                        "property setter " + setter + " must accept exactly " + field.asType());
            }
        }
    }

    private void checkAccessorReuse(
            Map<String, VariableElement> usedAccessors,
            String accessor,
            VariableElement field,
            AnnotationMirror property) {
        VariableElement previous = usedAccessors.putIfAbsent(accessor, field);
        if (previous != null && previous != field) {
            error(
                    field,
                    property,
                    "property accessor "
                            + accessor
                            + " is already used by "
                            + previous.getSimpleName());
        }
    }

    private List<ExecutableElement> methodsNamed(TypeElement type, String name) {
        return ElementFilter.methodsIn(type.getEnclosedElements()).stream()
                .filter(method -> method.getSimpleName().contentEquals(name))
                .toList();
    }

    private boolean isPublicInstance(ExecutableElement method) {
        return method.getModifiers().contains(Modifier.PUBLIC)
                && !method.getModifiers().contains(Modifier.STATIC);
    }

    private Optional<ExecutableElement> validateSignal(Element element) {
        if (element.getKind() != ElementKind.INTERFACE) {
            error(element, "@FoundrySignal must annotate an interface");
            return Optional.empty();
        }
        TypeElement signal = (TypeElement) element;
        List<ExecutableElement> methods = effectiveAbstractMethods(signal);
        if (!elements.isFunctionalInterface(signal) || methods.size() != 1) {
            error(signal, "signal must declare exactly one abstract method");
            return Optional.empty();
        }
        ExecutableElement method = methods.get(0);
        if (method.getReturnType().getKind() != TypeKind.VOID) {
            error(method, "signal method must return void");
        }
        method.getParameters()
                .forEach(parameter -> validateSupported(parameter.asType(), parameter));
        return Optional.of(method);
    }

    private List<ExecutableElement> effectiveAbstractMethods(TypeElement signal) {
        TypeElement objectType = elements.getTypeElement("java.lang.Object");
        List<ExecutableElement> objectMethods =
                objectType == null
                        ? List.of()
                        : ElementFilter.methodsIn(elements.getAllMembers(objectType));
        Map<String, ExecutableElement> methods = new LinkedHashMap<>();
        ElementFilter.methodsIn(elements.getAllMembers(signal)).stream()
                .filter(method -> method.getModifiers().contains(Modifier.ABSTRACT))
                .filter(
                        method ->
                                objectMethods.stream()
                                        .filter(
                                                objectMethod ->
                                                        objectMethod
                                                                .getModifiers()
                                                                .contains(Modifier.PUBLIC))
                                        .noneMatch(
                                                objectMethod ->
                                                        sameSignature(objectMethod, method)))
                .forEach(method -> methods.putIfAbsent(methodSignatureKey(method), method));
        return List.copyOf(methods.values());
    }

    private String methodSignatureKey(ExecutableElement method) {
        return method.getSimpleName()
                + method.getParameters().stream()
                        .map(parameter -> types.erasure(parameter.asType()).toString())
                        .collect(java.util.stream.Collectors.joining(",", "(", ")"));
    }

    private ExtensionModel.SignalModel signalModel(
            TypeElement signal, String exported, ExecutableElement method) {
        return new ExtensionModel.SignalModel(
                signal.getSimpleName().toString(),
                exported,
                method == null ? List.of() : parameters(method));
    }

    private boolean matchesBaseVirtual(
            TypeMirror base, ExecutableElement override, String exportedName) {
        Element baseElement = types.asElement(base);
        if (!(baseElement instanceof TypeElement baseType)) {
            return false;
        }
        return ElementFilter.methodsIn(elements.getAllMembers(baseType)).stream()
                .filter(method -> method.getSimpleName().contentEquals(exportedName))
                .filter(method -> annotation(method, GENERATED).isPresent())
                .anyMatch(method -> sameSignature(method, override));
    }

    private boolean sameSignature(ExecutableElement expected, ExecutableElement actual) {
        if (!types.isSameType(expected.getReturnType(), actual.getReturnType())
                || expected.getParameters().size() != actual.getParameters().size()) {
            return false;
        }
        for (int index = 0; index < expected.getParameters().size(); index++) {
            if (!types.isSameType(
                    expected.getParameters().get(index).asType(),
                    actual.getParameters().get(index).asType())) {
                return false;
            }
        }
        return true;
    }

    private List<String> initializationDependencies(
            TypeElement extension, AnnotationMirror initialization) {
        AnnotationValue afterValue = value(initialization, "after");
        @SuppressWarnings("unchecked")
        List<? extends AnnotationValue> values =
                (List<? extends AnnotationValue>) afterValue.getValue();
        List<String> dependencies = new ArrayList<>();
        Set<String> uniqueDependencies = new HashSet<>();
        for (AnnotationValue dependencyValue : values) {
            TypeMirror dependency = (TypeMirror) dependencyValue.getValue();
            Element dependencyElement = types.asElement(dependency);
            if (!(dependencyElement instanceof TypeElement dependencyType)
                    || annotation(dependencyType, CLASS).isEmpty()) {
                error(
                        extension,
                        initialization,
                        dependencyValue,
                        "initialization dependency must be a @FoundryClass: " + dependency);
            } else {
                String dependencyName = dependencyType.getQualifiedName().toString();
                if (!uniqueDependencies.add(dependencyName)) {
                    error(
                            extension,
                            initialization,
                            dependencyValue,
                            "duplicate initialization dependency " + dependencyName);
                } else {
                    dependencies.add(dependencyName);
                }
            }
        }
        return List.copyOf(dependencies);
    }

    private ExtensionModel.MethodModel methodModel(ExecutableElement method, String exportedName) {
        return new ExtensionModel.MethodModel(
                method.getSimpleName().toString(),
                exportedName,
                method.getReturnType().toString(),
                parameters(method));
    }

    private List<ExtensionModel.ParameterModel> parameters(ExecutableElement method) {
        return method.getParameters().stream()
                .map(
                        parameter ->
                                new ExtensionModel.ParameterModel(
                                        parameter.getSimpleName().toString(),
                                        parameter.asType().toString()))
                .toList();
    }

    private void checkDuplicate(Map<String, Element> names, String name, Element element) {
        Element previous = names.putIfAbsent(name, element);
        if (previous != null) {
            error(element, "duplicate exported name " + name);
        }
    }

    private String exportedName(Element element, AnnotationMirror annotation, String fallback) {
        String configured = stringValue(annotation, "name");
        String exported = configured.isEmpty() ? fallback : configured;
        if (!exported.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            error(
                    element,
                    annotation,
                    value(annotation, "name"),
                    "invalid exported name " + exported);
        }
        return exported;
    }

    private String stringValue(AnnotationMirror annotation, String name) {
        return (String) value(annotation, name).getValue();
    }

    private Optional<AnnotationMirror> annotation(Element element, String qualifiedName) {
        return element.getAnnotationMirrors().stream()
                .filter(
                        mirror ->
                                ((TypeElement) mirror.getAnnotationType().asElement())
                                        .getQualifiedName()
                                        .contentEquals(qualifiedName))
                .map(AnnotationMirror.class::cast)
                .findFirst();
    }

    private AnnotationValue value(AnnotationMirror annotation, String name) {
        return elements.getElementValuesWithDefaults(annotation).entrySet().stream()
                .filter(entry -> entry.getKey().getSimpleName().contentEquals(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
    }

    private void error(Element element, String message) {
        errorCount++;
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private void error(Element element, AnnotationMirror annotation, String message) {
        errorCount++;
        messager.printMessage(Diagnostic.Kind.ERROR, message, element, annotation);
    }

    private void error(
            Element element, AnnotationMirror annotation, AnnotationValue value, String message) {
        errorCount++;
        messager.printMessage(Diagnostic.Kind.ERROR, message, element, annotation, value);
    }
}
