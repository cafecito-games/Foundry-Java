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
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

final class ExtensionValidator {
    static final String CLASS = "games.cafecito.foundry.annotations.FoundryClass";
    static final String CONSTANT = "games.cafecito.foundry.annotations.FoundryConstant";
    static final String ENUM_VALUE = "games.cafecito.foundry.annotations.FoundryEnumValue";
    static final String METHOD = "games.cafecito.foundry.annotations.FoundryMethod";
    static final String PROPERTY = "games.cafecito.foundry.annotations.FoundryProperty";
    static final String SIGNAL = "games.cafecito.foundry.annotations.FoundrySignal";
    static final String OVERRIDE = "games.cafecito.foundry.annotations.FoundryOverride";
    static final String INITIALIZATION = "games.cafecito.foundry.annotations.FoundryInitialization";
    static final String GENERATED = "games.cafecito.foundry.annotations.GeneratedByFoundry";
    static final String VIRTUAL = "games.cafecito.foundry.annotations.FoundryVirtual";

    private final Types types;
    private final Elements elements;
    private final Messager messager;
    private final Map<String, Optional<ExtensionModel.EnumModel>> enumCache = new LinkedHashMap<>();
    private final Map<String, Boolean> enumAccessibilityCache = new LinkedHashMap<>();
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
        int errorsBefore = errorCount;
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
        boolean bindingConstructor = validateConstructor(extension, base);

        List<ExtensionModel.MethodModel> methods = new ArrayList<>();
        List<ExtensionModel.MethodModel> overrides = new ArrayList<>();
        List<ExtensionModel.ConstantModel> constants = new ArrayList<>();
        List<ExtensionModel.PropertyModel> properties = new ArrayList<>();
        List<ExtensionModel.SignalModel> signals = new ArrayList<>();
        EnumInventory enumInventory = new EnumInventory();
        Map<String, Element> exportedNames = new LinkedHashMap<>();
        Map<String, VariableElement> propertyAccessors = new LinkedHashMap<>();
        Map<String, Element> methodDispatchNames = new LinkedHashMap<>();
        Map<String, Element> getterDispatchNames = new LinkedHashMap<>();
        Map<String, Element> setterDispatchNames = new LinkedHashMap<>();

        for (Element member : extension.getEnclosedElements()) {
            annotation(member, CONSTANT)
                    .ifPresent(
                            mirror -> {
                                VariableElement field = (VariableElement) member;
                                String exported =
                                        exportedName(
                                                field, mirror, field.getSimpleName().toString());
                                checkDuplicate(exportedNames, exported, field);
                                validateConstant(field, mirror, exported).ifPresent(constants::add);
                            });
            annotation(member, METHOD)
                    .ifPresent(
                            mirror -> {
                                ExecutableElement method = (ExecutableElement) member;
                                String exported =
                                        exportedName(
                                                method, mirror, method.getSimpleName().toString());
                                validateCallable(method, "exported method");
                                validateMethodTypes(method, extension, enumInventory);
                                checkDuplicate(exportedNames, exported, method);
                                checkDispatchDuplicate(
                                        methodDispatchNames,
                                        method.getSimpleName().toString(),
                                        method,
                                        "method");
                                methods.add(methodModel(method, exported));
                            });
            annotation(member, OVERRIDE)
                    .ifPresent(
                            mirror -> {
                                ExecutableElement method = (ExecutableElement) member;
                                Optional<String> virtualIdentity =
                                        baseVirtualIdentity(base, method);
                                String requestedIdentity = stringValue(mirror, "name");
                                String exported =
                                        virtualIdentity.orElse(
                                                requestedIdentity.isEmpty()
                                                        ? method.getSimpleName().toString()
                                                        : requestedIdentity);
                                validateCallable(method, "Foundry override");
                                validateMethodTypes(method, extension, enumInventory);
                                if (virtualIdentity.isEmpty()) {
                                    error(
                                            method,
                                            "Foundry override "
                                                    + exported
                                                    + " does not match a generated Foundry virtual method on "
                                                    + base);
                                } else if (!requestedIdentity.isEmpty()
                                        && !requestedIdentity.equals(virtualIdentity.get())) {
                                    error(
                                            method,
                                            "Foundry override name "
                                                    + requestedIdentity
                                                    + " does not match generated Foundry virtual identity "
                                                    + virtualIdentity.get());
                                }
                                checkDuplicate(exportedNames, exported, method);
                                checkDispatchDuplicate(
                                        methodDispatchNames,
                                        method.getSimpleName().toString(),
                                        method,
                                        "method");
                                overrides.add(methodModel(method, exported));
                            });
            annotation(member, PROPERTY)
                    .ifPresent(
                            mirror -> {
                                VariableElement field = (VariableElement) member;
                                String exported =
                                        exportedName(
                                                field, mirror, field.getSimpleName().toString());
                                validateSupported(field.asType(), field, extension, enumInventory);
                                validateProperty(extension, field, mirror, propertyAccessors);
                                checkDuplicate(exportedNames, exported, field);
                                checkAccessorDispatchDuplicate(
                                        getterDispatchNames,
                                        stringValue(mirror, "getter"),
                                        field,
                                        "property getter");
                                checkAccessorDispatchDuplicate(
                                        setterDispatchNames,
                                        stringValue(mirror, "setter"),
                                        field,
                                        "property setter");
                                properties.add(
                                        new ExtensionModel.PropertyModel(
                                                field.getSimpleName().toString(),
                                                exported,
                                                field.asType().toString(),
                                                stringValue(mirror, "getter"),
                                                stringValue(mirror, "setter"),
                                                intValue(mirror, "index"),
                                                stringValue(mirror, "groupName"),
                                                stringValue(mirror, "groupPrefix"),
                                                stringValue(mirror, "subgroupName"),
                                                stringValue(mirror, "subgroupPrefix")));
                            });
            annotation(member, SIGNAL)
                    .ifPresent(
                            mirror -> {
                                String exported =
                                        exportedName(
                                                member, mirror, member.getSimpleName().toString());
                                Optional<ResolvedMethod> signalMethod =
                                        validateSignal(member, extension, enumInventory);
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
        String exportedClassName =
                exportedName(extension, classAnnotation, extension.getSimpleName().toString());
        if (errorCount != errorsBefore || !enumInventory.valid) {
            return Optional.empty();
        }
        return Optional.of(
                new ExtensionModel(
                        qualifiedName,
                        elements.getPackageOf(extension).getQualifiedName().toString(),
                        extension.getSimpleName().toString(),
                        exportedClassName,
                        base.toString(),
                        baseFoundryName(base),
                        initializationLevel,
                        bindingConstructor,
                        dependencies,
                        methods,
                        constants,
                        properties,
                        signals,
                        overrides,
                        List.copyOf(enumInventory.models.values())));
    }

    void validateAnnotationPlacement(RoundEnvironment roundEnvironment) {
        TypeElement enumValue = elements.getTypeElement(ENUM_VALUE);
        if (enumValue != null) {
            for (Element annotated : roundEnvironment.getElementsAnnotatedWith(enumValue)) {
                if (annotated.getKind() != ElementKind.ENUM_CONSTANT) {
                    error(annotated, "@FoundryEnumValue may only annotate an enum constant");
                }
            }
        }
        for (String annotationName :
                List.of(CONSTANT, METHOD, PROPERTY, SIGNAL, OVERRIDE, INITIALIZATION)) {
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
        models.forEach(
                (name, model) ->
                        model.initializationDependencies().stream()
                                .filter(dependency -> !models.containsKey(dependency))
                                .forEach(
                                        dependency -> {
                                            TypeElement element = sourceElements.get(name);
                                            AnnotationMirror mirror =
                                                    annotation(element, INITIALIZATION)
                                                            .orElseThrow();
                                            error(
                                                    element,
                                                    mirror,
                                                    "initialization dependency must be part of "
                                                            + "the current compilation: "
                                                            + dependency);
                                        }));
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

    private boolean validateConstructor(TypeElement extension, TypeMirror base) {
        List<ExecutableElement> constructors =
                ElementFilter.constructorsIn(extension.getEnclosedElements());
        TypeElement bindingContext =
                elements.getTypeElement("games.cafecito.foundry.runtime.FoundryBindingContext");
        TypeElement objectLease =
                elements.getTypeElement("games.cafecito.foundry.runtime.ObjectLease");
        Optional<ExecutableElement> publicBindingConstructor =
                constructors.stream()
                        .filter(constructor -> constructor.getModifiers().contains(Modifier.PUBLIC))
                        .filter(constructor -> constructor.getParameters().size() == 2)
                        .filter(
                                constructor ->
                                        bindingContext != null
                                                && objectLease != null
                                                && types.isSameType(
                                                        constructor.getParameters().get(0).asType(),
                                                        bindingContext.asType())
                                                && types.isSameType(
                                                        constructor.getParameters().get(1).asType(),
                                                        objectLease.asType()))
                        .findFirst();
        if (publicBindingConstructor.isPresent()) {
            publicBindingConstructor
                    .filter(this::declaresCheckedException)
                    .ifPresent(
                            constructor ->
                                    error(
                                            constructor,
                                            "extension constructor cannot declare checked exceptions"));
            return true;
        }
        if (base.toString().startsWith("games.cafecito.foundry.generated.")) {
            error(
                    extension,
                    "extension class must provide a public constructor accepting "
                            + "FoundryBindingContext and ObjectLease");
            return false;
        }
        Optional<ExecutableElement> publicZeroArgument =
                constructors.stream()
                        .filter(constructor -> constructor.getParameters().isEmpty())
                        .filter(constructor -> constructor.getModifiers().contains(Modifier.PUBLIC))
                        .findFirst();
        if (publicZeroArgument.isEmpty()) {
            error(extension, "extension class must provide a public zero-argument constructor");
        }
        publicZeroArgument
                .filter(this::declaresCheckedException)
                .ifPresent(
                        constructor ->
                                error(
                                        constructor,
                                        "extension constructor cannot declare checked exceptions"));
        return false;
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

    private void validateMethodTypes(
            ExecutableElement method, TypeElement extension, EnumInventory enumInventory) {
        validateSupported(
                method.getReturnType(),
                method,
                "unsupported Foundry return type ",
                extension,
                enumInventory);
        method.getParameters()
                .forEach(
                        parameter ->
                                validateSupported(
                                        parameter.asType(),
                                        parameter,
                                        "unsupported Foundry parameter type ",
                                        extension,
                                        enumInventory));
    }

    private void validateSupported(
            TypeMirror type, Element source, TypeElement extension, EnumInventory enumInventory) {
        validateSupported(type, source, "unsupported Foundry type ", extension, enumInventory);
    }

    private void validateSupported(
            TypeMirror type,
            Element source,
            String messagePrefix,
            TypeElement extension,
            EnumInventory enumInventory) {
        if (type.getKind().isPrimitive() || type.getKind() == TypeKind.VOID) {
            return;
        }
        if (type.getKind() == TypeKind.DECLARED) {
            TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
            String name = element.getQualifiedName().toString();
            if (element.getKind() == ElementKind.ENUM) {
                trackEnum(element, extension, enumInventory);
                return;
            }
            if (name.equals("java.lang.String")
                    || name.startsWith("games.cafecito.foundry.api.")
                    || name.startsWith("games.cafecito.foundry.types.")
                    || generatedType(element)
                    || annotation(element, CLASS).isPresent()) {
                return;
            }
        }
        error(source, messagePrefix + type);
    }

    private void trackEnum(
            TypeElement enumType, TypeElement extension, EnumInventory enumInventory) {
        String qualifiedName = enumType.getQualifiedName().toString();
        Optional<ExtensionModel.EnumModel> analysis =
                enumCache.computeIfAbsent(qualifiedName, ignored -> analyzeEnum(enumType));
        if (analysis.isEmpty()) {
            enumInventory.valid = false;
            return;
        }
        ExtensionModel.EnumModel enumModel = analysis.orElseThrow();
        if (enumModel.origin() == ExtensionModel.EnumOrigin.USER
                && !enumAccessible(enumType, extension)) {
            enumInventory.valid = false;
            return;
        }
        enumInventory.models.putIfAbsent(qualifiedName, enumModel);
    }

    private Optional<ExtensionModel.EnumModel> analyzeEnum(TypeElement enumType) {
        String qualifiedName = enumType.getQualifiedName().toString();
        if (generatedType(enumType)) {
            if (!hasGeneratedEnumConversionApi(enumType)) {
                error(
                        enumType,
                        "generated Foundry enum must declare public long value() and public static "
                                + qualifiedName
                                + " fromValue(long)");
                return Optional.empty();
            }
            return Optional.of(
                    new ExtensionModel.EnumModel(
                            qualifiedName, ExtensionModel.EnumOrigin.GENERATED, List.of()));
        }
        List<VariableElement> constants =
                ElementFilter.fieldsIn(enumType.getEnclosedElements()).stream()
                        .filter(field -> field.getKind() == ElementKind.ENUM_CONSTANT)
                        .toList();
        if (constants.isEmpty()) {
            error(
                    enumType,
                    "user enum " + qualifiedName + " must declare at least one enum constant");
            return Optional.empty();
        }
        boolean valid = true;
        Map<Long, VariableElement> values = new LinkedHashMap<>();
        List<ExtensionModel.EnumConstantModel> models = new ArrayList<>();
        for (VariableElement constant : constants) {
            Optional<AnnotationMirror> mapping = annotation(constant, ENUM_VALUE);
            if (mapping.isEmpty()) {
                error(
                        constant,
                        "enum constant "
                                + constant.getSimpleName()
                                + " must declare @FoundryEnumValue");
                valid = false;
                continue;
            }
            AnnotationValue configuredValue = value(mapping.orElseThrow(), "value");
            long configured = (Long) configuredValue.getValue();
            VariableElement previous = values.putIfAbsent(configured, constant);
            if (previous != null) {
                error(
                        constant,
                        mapping.orElseThrow(),
                        configuredValue,
                        "duplicate @FoundryEnumValue "
                                + configured
                                + "; already used by "
                                + previous.getSimpleName());
                valid = false;
                continue;
            }
            models.add(
                    new ExtensionModel.EnumConstantModel(
                            constant.getSimpleName().toString(), configured));
        }
        if (!valid) {
            return Optional.empty();
        }
        return Optional.of(
                new ExtensionModel.EnumModel(
                        qualifiedName, ExtensionModel.EnumOrigin.USER, models));
    }

    private boolean hasGeneratedEnumConversionApi(TypeElement enumType) {
        List<ExecutableElement> methods = ElementFilter.methodsIn(elements.getAllMembers(enumType));
        TypeMirror longType = types.getPrimitiveType(TypeKind.LONG);
        boolean value =
                methods.stream()
                        .filter(method -> method.getSimpleName().contentEquals("value"))
                        .anyMatch(
                                method ->
                                        method.getModifiers().contains(Modifier.PUBLIC)
                                                && !method.getModifiers().contains(Modifier.STATIC)
                                                && method.getParameters().isEmpty()
                                                && types.isSameType(
                                                        method.getReturnType(), longType));
        boolean fromValue =
                methods.stream()
                        .filter(method -> method.getSimpleName().contentEquals("fromValue"))
                        .anyMatch(
                                method ->
                                        method.getModifiers().contains(Modifier.PUBLIC)
                                                && method.getModifiers().contains(Modifier.STATIC)
                                                && method.getParameters().size() == 1
                                                && types.isSameType(
                                                        method.getParameters().get(0).asType(),
                                                        longType)
                                                && types.isSameType(
                                                        method.getReturnType(), enumType.asType()));
        return value && fromValue;
    }

    private boolean enumAccessible(TypeElement enumType, TypeElement extension) {
        String extensionPackage = elements.getPackageOf(extension).getQualifiedName().toString();
        String key = enumType.getQualifiedName() + "\u0000" + extensionPackage;
        return enumAccessibilityCache.computeIfAbsent(
                key, ignored -> validateEnumAccessibility(enumType, extensionPackage));
    }

    private boolean validateEnumAccessibility(TypeElement enumType, String extensionPackage) {
        boolean samePackage =
                elements.getPackageOf(enumType).getQualifiedName().contentEquals(extensionPackage);
        Element current = enumType;
        while (current instanceof TypeElement currentType) {
            boolean inaccessible =
                    samePackage
                            ? currentType.getModifiers().contains(Modifier.PRIVATE)
                            : !currentType.getModifiers().contains(Modifier.PUBLIC);
            if (inaccessible) {
                String reason =
                        currentType.getModifiers().contains(Modifier.PRIVATE)
                                ? " is private"
                                : " is not public";
                error(
                        enumType,
                        "user enum "
                                + enumType.getQualifiedName()
                                + " is not accessible to its generated sibling trampoline because "
                                + currentType.getQualifiedName()
                                + reason);
                return false;
            }
            current = currentType.getEnclosingElement();
        }
        return true;
    }

    private boolean generatedType(TypeElement type) {
        Element current = type;
        while (current instanceof TypeElement currentType) {
            if (annotation(currentType, GENERATED).isPresent()) {
                return true;
            }
            current = currentType.getEnclosingElement();
        }
        return false;
    }

    private Optional<ExtensionModel.ConstantModel> validateConstant(
            VariableElement field, AnnotationMirror constant, String exportedName) {
        int errorsBefore = errorCount;
        String fieldName = field.getSimpleName().toString();
        boolean staticFinal =
                field.getModifiers().contains(Modifier.STATIC)
                        && field.getModifiers().contains(Modifier.FINAL);
        if (!staticFinal) {
            error(field, constant, "Foundry constant " + fieldName + " must be static final");
        }
        TypeKind kind = field.asType().getKind();
        boolean integral =
                kind == TypeKind.BYTE
                        || kind == TypeKind.SHORT
                        || kind == TypeKind.INT
                        || kind == TypeKind.LONG
                        || kind == TypeKind.CHAR;
        if (!integral) {
            error(
                    field,
                    constant,
                    "Foundry constant " + fieldName + " must use byte, short, int, long, or char");
        }
        Object rawValue = field.getConstantValue();
        if (staticFinal && integral && rawValue == null) {
            error(
                    field,
                    constant,
                    "Foundry constant " + fieldName + " must have a compile-time integral value");
        }
        String enumName = optionalMetadata(field, constant, "enumName", "constant enumName");
        boolean bitfield = booleanValue(constant, "bitfield");
        if (bitfield && enumName.isEmpty()) {
            error(
                    field,
                    constant,
                    value(constant, "bitfield"),
                    "bitfield constant must declare a non-empty enumName");
        }
        if (errorCount != errorsBefore) {
            return Optional.empty();
        }
        long value =
                rawValue instanceof Character character
                        ? character.charValue()
                        : ((Number) rawValue).longValue();
        return Optional.of(
                new ExtensionModel.ConstantModel(
                        fieldName,
                        exportedName,
                        field.asType().toString(),
                        enumName,
                        value,
                        bitfield));
    }

    private void validateProperty(
            TypeElement extension,
            VariableElement field,
            AnnotationMirror property,
            Map<String, VariableElement> usedAccessors) {
        String getter = stringValue(property, "getter");
        String setter = stringValue(property, "setter");
        int index = intValue(property, "index");
        String groupName = optionalMetadata(field, property, "groupName", "property groupName");
        String groupPrefix =
                optionalMetadata(field, property, "groupPrefix", "property groupPrefix");
        String subgroupName =
                optionalMetadata(field, property, "subgroupName", "property subgroupName");
        String subgroupPrefix =
                optionalMetadata(field, property, "subgroupPrefix", "property subgroupPrefix");
        if (index < -1) {
            error(
                    field,
                    property,
                    value(property, "index"),
                    "property index must be -1 or non-negative");
        }
        if (groupName.isEmpty() && !groupPrefix.isEmpty()) {
            error(
                    field,
                    property,
                    value(property, "groupPrefix"),
                    "property groupPrefix requires a non-empty groupName");
        }
        if (subgroupName.isEmpty() && !subgroupPrefix.isEmpty()) {
            error(
                    field,
                    property,
                    value(property, "subgroupPrefix"),
                    "property subgroupPrefix requires a non-empty subgroupName");
        }
        if (getter.isEmpty()) {
            error(field, property, value(property, "getter"), "property getter must be specified");
        } else if (getter.isBlank()) {
            error(field, property, value(property, "getter"), "property getter must be non-blank");
        } else {
            checkAccessorReuse(usedAccessors, getter, field, property);
            List<ExecutableElement> candidates = methodsNamed(extension, getter);
            Optional<ExecutableElement> matchingGetter =
                    candidates.stream()
                            .filter(
                                    method ->
                                            isPublicInstance(method)
                                                    && method.getParameters().isEmpty()
                                                    && types.isSameType(
                                                            method.getReturnType(), field.asType()))
                            .findFirst();
            if (matchingGetter.isEmpty()) {
                error(
                        field,
                        property,
                        "property getter " + getter + " must return " + field.asType());
            } else {
                matchingGetter
                        .filter(this::declaresCheckedException)
                        .ifPresent(
                                method ->
                                        error(
                                                field,
                                                property,
                                                "property getter "
                                                        + getter
                                                        + " cannot declare checked exceptions"));
            }
        }
        if (!setter.isEmpty()) {
            if (setter.isBlank()) {
                error(
                        field,
                        property,
                        value(property, "setter"),
                        "property setter must be empty or non-blank");
                return;
            }
            checkAccessorReuse(usedAccessors, setter, field, property);
            List<ExecutableElement> candidates = methodsNamed(extension, setter);
            Optional<ExecutableElement> matchingSetter =
                    candidates.stream()
                            .filter(
                                    method ->
                                            isPublicInstance(method)
                                                    && method.getReturnType().getKind()
                                                            == TypeKind.VOID
                                                    && method.getParameters().size() == 1
                                                    && types.isSameType(
                                                            method.getParameters().get(0).asType(),
                                                            field.asType()))
                            .findFirst();
            if (matchingSetter.isEmpty()) {
                error(
                        field,
                        property,
                        "property setter " + setter + " must accept exactly " + field.asType());
            } else {
                matchingSetter
                        .filter(this::declaresCheckedException)
                        .ifPresent(
                                method ->
                                        error(
                                                field,
                                                property,
                                                "property setter "
                                                        + setter
                                                        + " cannot declare checked exceptions"));
            }
        }
    }

    private String optionalMetadata(
            Element element,
            AnnotationMirror annotation,
            String memberName,
            String diagnosticName) {
        String configured = stringValue(annotation, memberName);
        if (!configured.isEmpty() && configured.isBlank()) {
            error(
                    element,
                    annotation,
                    value(annotation, memberName),
                    diagnosticName + " must be empty or non-blank");
        }
        return configured;
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

    private Optional<ResolvedMethod> validateSignal(
            Element element, TypeElement extension, EnumInventory enumInventory) {
        if (element.getKind() != ElementKind.INTERFACE) {
            error(element, "@FoundrySignal must annotate an interface");
            return Optional.empty();
        }
        TypeElement signal = (TypeElement) element;
        List<ResolvedMethod> methods = effectiveAbstractMethods(signal);
        if (methods.size() != 1) {
            error(signal, "signal must declare exactly one abstract method");
            return Optional.empty();
        }
        ResolvedMethod method = methods.get(0);
        if (method.type().getReturnType().getKind() != TypeKind.VOID) {
            error(method.element(), "signal method must return void");
        }
        for (int index = 0; index < method.element().getParameters().size(); index++) {
            validateSupported(
                    method.type().getParameterTypes().get(index),
                    method.element().getParameters().get(index),
                    extension,
                    enumInventory);
        }
        return Optional.of(method);
    }

    private List<ResolvedMethod> effectiveAbstractMethods(TypeElement signal) {
        TypeElement objectType = elements.getTypeElement("java.lang.Object");
        List<ExecutableElement> objectMethods =
                objectType == null
                        ? List.of()
                        : ElementFilter.methodsIn(elements.getAllMembers(objectType));
        DeclaredType signalType = (DeclaredType) signal.asType();
        Map<String, ResolvedMethod> methods = new LinkedHashMap<>();
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
                .map(
                        method ->
                                new ResolvedMethod(
                                        method,
                                        (ExecutableType) types.asMemberOf(signalType, method)))
                .forEach(method -> methods.putIfAbsent(methodSignatureKey(method), method));
        return List.copyOf(methods.values());
    }

    private String methodSignatureKey(ResolvedMethod method) {
        return method.element().getSimpleName()
                + method.type().getParameterTypes().stream()
                        .map(parameter -> types.erasure(parameter).toString())
                        .collect(java.util.stream.Collectors.joining(",", "(", ")"));
    }

    private ExtensionModel.SignalModel signalModel(
            TypeElement signal, String exported, ResolvedMethod method) {
        return new ExtensionModel.SignalModel(
                signal.getSimpleName().toString(),
                exported,
                method == null ? List.of() : parameters(method.element(), method.type()));
    }

    /**
     * Resolves the engine class name the extension's parent is registered under. The engine looks a
     * parent up through {@code ClassDB}, which only knows engine class names, so the Java binding
     * type's qualified name would never resolve. The generator names every generated engine class
     * root after the engine class it binds, which makes the simple name the engine name.
     */
    private String baseFoundryName(TypeMirror base) {
        Element baseElement = types.asElement(base);
        return baseElement == null ? base.toString() : baseElement.getSimpleName().toString();
    }

    private Optional<String> baseVirtualIdentity(TypeMirror base, ExecutableElement override) {
        Element baseElement = types.asElement(base);
        if (!(baseElement instanceof TypeElement baseType)) {
            return Optional.empty();
        }
        return ElementFilter.methodsIn(elements.getAllMembers(baseType)).stream()
                .filter(method -> method.getSimpleName().contentEquals(override.getSimpleName()))
                .filter(method -> annotation(method, VIRTUAL).isPresent())
                .filter(method -> sameSignature(method, override))
                .map(method -> stringValue(annotation(method, VIRTUAL).orElseThrow(), "value"))
                .findFirst();
    }

    private boolean sameSignature(ExecutableElement expected, ExecutableElement actual) {
        if (!expected.getSimpleName().contentEquals(actual.getSimpleName())
                || !types.isSameType(expected.getReturnType(), actual.getReturnType())
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

    private List<ExtensionModel.ParameterModel> parameters(
            ExecutableElement method, ExecutableType resolvedType) {
        List<ExtensionModel.ParameterModel> parameters = new ArrayList<>();
        for (int index = 0; index < method.getParameters().size(); index++) {
            parameters.add(
                    new ExtensionModel.ParameterModel(
                            method.getParameters().get(index).getSimpleName().toString(),
                            resolvedType.getParameterTypes().get(index).toString()));
        }
        return List.copyOf(parameters);
    }

    private record ResolvedMethod(ExecutableElement element, ExecutableType type) {}

    private static final class EnumInventory {
        private final Map<String, ExtensionModel.EnumModel> models = new LinkedHashMap<>();
        private boolean valid = true;
    }

    /**
     * Rejects members that would share a generated dispatch key.
     *
     * <p>The native bridge resolves an exported name to a member descriptor and then dispatches
     * into the generated trampoline by that descriptor's Java name, so two exported members of one
     * class cannot share a Java name even when their exported names differ.
     */
    private void checkDispatchDuplicate(
            Map<String, Element> names, String name, Element element, String kind) {
        Element previous = names.putIfAbsent(name, element);
        if (previous != null) {
            error(
                    element,
                    "duplicate Java "
                            + kind
                            + " name "
                            + name
                            + "; exported members are dispatched by Java name and must not share one");
        }
    }

    private void checkAccessorDispatchDuplicate(
            Map<String, Element> names, String name, Element element, String kind) {
        if (name.isEmpty()) {
            return;
        }
        checkDispatchDuplicate(names, name, element, kind);
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

    private int intValue(AnnotationMirror annotation, String name) {
        return (Integer) value(annotation, name).getValue();
    }

    private boolean booleanValue(AnnotationMirror annotation, String name) {
        return (Boolean) value(annotation, name).getValue();
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
