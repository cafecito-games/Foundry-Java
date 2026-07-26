package games.cafecito.foundry.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.annotation.processing.Filer;
import javax.lang.model.element.TypeElement;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

final class ModuleEmitter {
    private static final String PROCESSOR =
            "games.cafecito.foundry.processor.FoundryExtensionProcessor";
    private final Filer filer;

    ModuleEmitter(Filer filer) {
        this.filer = filer;
    }

    ReservedRegistry reserve(String moduleName, TypeElement source) throws IOException {
        String packageName = registryPackage(moduleName);
        String className = registryClassName(moduleName);
        String qualifiedName = packageName + "." + className;
        return new ReservedRegistry(
                moduleName,
                packageName,
                className,
                qualifiedName,
                filer.createSourceFile(qualifiedName, source));
    }

    void emit(ReservedRegistry reserved, List<ExtensionModel> unsorted, List<TypeElement> sources)
            throws IOException {
        List<ExtensionModel> models =
                unsorted.stream()
                        .sorted(Comparator.comparing(ExtensionModel::qualifiedName))
                        .toList();
        TypeElement[] origins = sources.toArray(TypeElement[]::new);
        FileObject descriptor =
                filer.createResource(
                        StandardLocation.CLASS_OUTPUT,
                        "",
                        "META-INF/foundry-java/modules/" + reserved.moduleName() + ".descriptor",
                        origins);
        FileObject keepRules =
                filer.createResource(
                        StandardLocation.CLASS_OUTPUT,
                        "",
                        "META-INF/proguard/foundry-java-" + reserved.moduleName() + ".pro",
                        origins);
        try (Writer writer = reserved.source().openWriter()) {
            writer.write(
                    registrySource(
                            reserved.moduleName(),
                            reserved.packageName(),
                            reserved.className(),
                            models));
        }
        writeResource(
                descriptor, descriptor(reserved.moduleName(), reserved.qualifiedName(), models));
        writeResource(
                keepRules, keepRules(reserved.moduleName(), reserved.qualifiedName(), models));
    }

    static String registryQualifiedName(String moduleName) {
        return registryPackage(moduleName) + "." + registryClassName(moduleName);
    }

    private void writeResource(FileObject resource, String contents) throws IOException {
        try (Writer writer = resource.openWriter()) {
            writer.write(contents);
        }
    }

    record ReservedRegistry(
            String moduleName,
            String packageName,
            String className,
            String qualifiedName,
            JavaFileObject source) {}

    private String registrySource(
            String moduleName, String packageName, String className, List<ExtensionModel> models) {
        String classes =
                models.stream()
                        .map(this::classDescriptorExpression)
                        .reduce((left, right) -> left + ",\n" + right)
                        .orElse("");
        return """
                package %s;

                @javax.annotation.processing.Generated("%s")
                public final class %s {
                    private %s() {}

                    private static final ModuleDescriptor DESCRIPTOR =
                            new ModuleDescriptor(
                                    "%s",
                                    java.util.List.of(
                %s));

                    public static ModuleDescriptor descriptor() {
                        return DESCRIPTOR;
                    }

                    public record ModuleDescriptor(String name, java.util.List<ClassDescriptor> classes) {
                        public ModuleDescriptor {
                            classes = java.util.List.copyOf(classes);
                        }
                    }

                    public record ClassDescriptor(
                            String javaName,
                            String foundryName,
                            String baseName,
                            String initializationLevel,
                            java.util.List<String> after,
                            ExtensionAccess access,
                            java.util.List<MemberDescriptor> members) {
                        public ClassDescriptor {
                            after = java.util.List.copyOf(after);
                            members = java.util.List.copyOf(members);
                        }
                    }

                    public record MemberDescriptor(
                            String kind, String foundryName, String javaName, String signature) {}

                    public interface ExtensionAccess {
                        Object construct(
                                games.cafecito.foundry.runtime.FoundryBindingContext context,
                                games.cafecito.foundry.runtime.ObjectLease lease);

                        Object invoke(Object target, String name, Object[] arguments);

                        Object getProperty(Object target, String name);

                        void setProperty(Object target, String name, Object value);
                    }
                }
                """
                .formatted(packageName, PROCESSOR, className, className, moduleName, classes);
    }

    private String classDescriptorExpression(ExtensionModel model) {
        String after =
                model.initializationDependencies().stream()
                        .map(value -> "\"" + value + "\"")
                        .reduce((left, right) -> left + ", " + right)
                        .orElse("");
        List<Member> members = members(model);
        String memberExpressions =
                members.stream()
                        .map(this::memberExpression)
                        .reduce((left, right) -> left + ",\n" + right)
                        .orElse("");
        return """
                                            new ClassDescriptor(
                                                    "%s",
                                                    "%s",
                                                    "%s",
                                                    "%s",
                                                    java.util.List.of(%s),
                                                    new ExtensionAccess() {
                                                        @Override
                                                        public Object construct(
                                                                games.cafecito.foundry.runtime.FoundryBindingContext context,
                                                                games.cafecito.foundry.runtime.ObjectLease lease) {
                                                            return %s_FoundryTrampoline.%s;
                                                        }

                                                        @Override
                                                        public Object invoke(
                                                                Object target, String name, Object[] arguments) {
                                                            return %s_FoundryTrampoline.invoke(
                                                                    target, name, arguments);
                                                        }

                                                        @Override
                                                        public Object getProperty(Object target, String name) {
                                                            return %s_FoundryTrampoline.getProperty(target, name);
                                                        }

                                                        @Override
                                                        public void setProperty(
                                                                Object target, String name, Object value) {
                                                            %s_FoundryTrampoline.setProperty(target, name, value);
                                                        }
                                                    },
                                                    java.util.List.of(
                %s))"""
                .formatted(
                        model.qualifiedName(),
                        model.exportedName(),
                        model.baseType(),
                        model.initializationLevel(),
                        after,
                        model.qualifiedName(),
                        model.bindingConstructor() ? "construct(context, lease)" : "construct()",
                        model.qualifiedName(),
                        model.qualifiedName(),
                        model.qualifiedName(),
                        memberExpressions);
    }

    private String descriptor(String moduleName, String registryName, List<ExtensionModel> models) {
        StringBuilder descriptor =
                new StringBuilder()
                        .append("format=1\n")
                        .append("module=")
                        .append(moduleName)
                        .append('\n')
                        .append("registry=")
                        .append(registryName)
                        .append('\n');
        for (ExtensionModel model : models) {
            descriptor
                    .append("class=")
                    .append(model.qualifiedName())
                    .append('|')
                    .append(model.exportedName())
                    .append('|')
                    .append(model.baseType())
                    .append('|')
                    .append(model.initializationLevel())
                    .append('|')
                    .append(String.join(",", model.initializationDependencies()))
                    .append('\n');
            for (Member member : members(model)) {
                descriptor
                        .append(member.kind())
                        .append('=')
                        .append(model.qualifiedName())
                        .append('|')
                        .append(member.foundryName())
                        .append('|')
                        .append(member.javaName())
                        .append('|')
                        .append(member.signature())
                        .append('\n');
            }
        }
        return descriptor.toString();
    }

    private String keepRules(String moduleName, String registryName, List<ExtensionModel> models) {
        StringBuilder rules =
                new StringBuilder()
                        .append("# Generated Foundry-Java entry points for ")
                        .append(moduleName)
                        .append(".\n")
                        .append("-keep class ")
                        .append(registryName)
                        .append(" { public static *** descriptor(); }\n");
        for (ExtensionModel model : models) {
            rules.append("-keep class ")
                    .append(model.qualifiedName())
                    .append("_FoundryTrampoline { public static *** *(...); }\n");
        }
        return rules.toString();
    }

    private List<Member> members(ExtensionModel model) {
        List<Member> members = new ArrayList<>();
        model.methods()
                .forEach(
                        method ->
                                members.add(
                                        new Member(
                                                "method",
                                                method.exportedName(),
                                                method.javaName(),
                                                signature(
                                                        method.returnType(),
                                                        method.parameters()))));
        model.overrides()
                .forEach(
                        method ->
                                members.add(
                                        new Member(
                                                "override",
                                                method.exportedName(),
                                                method.javaName(),
                                                signature(
                                                        method.returnType(),
                                                        method.parameters()))));
        model.properties()
                .forEach(
                        property ->
                                members.add(
                                        new Member(
                                                "property",
                                                property.exportedName(),
                                                property.fieldName(),
                                                property.type())));
        model.signals()
                .forEach(
                        signal ->
                                members.add(
                                        new Member(
                                                "signal",
                                                signal.exportedName(),
                                                signal.javaName(),
                                                signature("void", signal.parameters()))));
        return members.stream()
                .sorted(
                        Comparator.comparing(Member::foundryName)
                                .thenComparing(Member::kind)
                                .thenComparing(Member::javaName))
                .toList();
    }

    private String signature(String returnType, List<ExtensionModel.ParameterModel> parameters) {
        return returnType
                + "("
                + parameters.stream()
                        .map(ExtensionModel.ParameterModel::type)
                        .reduce((left, right) -> left + "," + right)
                        .orElse("")
                + ")";
    }

    private String memberExpression(Member member) {
        String indentation = "                                            ";
        String continuation = "                                                    ";
        String arguments =
                "\""
                        + member.kind()
                        + "\", \""
                        + member.foundryName()
                        + "\", \""
                        + member.javaName()
                        + "\", \""
                        + member.signature()
                        + "\")";
        if (continuation.length() + arguments.length() <= 95) {
            return indentation + "new MemberDescriptor(\n" + continuation + arguments;
        }
        return indentation
                + "new MemberDescriptor(\n"
                + continuation
                + "\""
                + member.kind()
                + "\",\n"
                + continuation
                + "\""
                + member.foundryName()
                + "\",\n"
                + continuation
                + "\""
                + member.javaName()
                + "\",\n"
                + continuation
                + "\""
                + member.signature()
                + "\")";
    }

    private static String registryPackage(String moduleName) {
        return "games.cafecito.foundry.generated." + moduleName.replace("-", "");
    }

    private static String registryClassName(String moduleName) {
        StringBuilder name = new StringBuilder();
        for (String part : moduleName.split("-")) {
            name.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
        }
        return name.append("Registry").toString();
    }

    private record Member(String kind, String foundryName, String javaName, String signature) {}
}
