package games.cafecito.foundry.processor;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.annotation.processing.Filer;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

final class ModuleEmitter {
    private static final String PROCESSOR =
            "games.cafecito.foundry.processor.FoundryExtensionProcessor";
    private static final String RUNTIME = "games.cafecito.foundry.runtime.FoundryRuntime";
    private final Filer filer;
    private final Elements elements;

    ModuleEmitter(Filer filer, Elements elements) {
        this.filer = filer;
        this.elements = elements;
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
        Provenance provenance = provenance();
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
                            reserved.qualifiedName(),
                            provenance,
                            models));
        }
        writeResource(
                descriptor,
                descriptor(reserved.moduleName(), reserved.qualifiedName(), provenance, models));
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
            String moduleName,
            String packageName,
            String className,
            String qualifiedName,
            Provenance provenance,
            List<ExtensionModel> models) {
        String classes =
                models.stream()
                        .map(this::classDescriptorExpression)
                        .reduce((left, right) -> left + ",\n" + right)
                        .orElse("");
        String decoder =
                models.stream()
                                .anyMatch(
                                        model ->
                                                !model.constants().isEmpty()
                                                        || !model.properties().isEmpty())
                        ? "\n"
                                + "    private static String decode(String value) {\n"
                                + "        return new String(\n"
                                + "                java.util.Base64.getUrlDecoder().decode(value),\n"
                                + "                java.nio.charset.StandardCharsets.UTF_8);\n"
                                + "    }\n"
                        : "";
        return """
                package %s;

                @javax.annotation.processing.Generated("%s")
                public final class %s
                        implements games.cafecito.foundry.runtime.FoundryModuleProvider {
                    public static final games.cafecito.foundry.runtime.FoundryModuleProvider PROVIDER =
                            new %s();

                    private %s() {}
                %s
                    private static final games.cafecito.foundry.runtime.FoundryModuleDescriptor DESCRIPTOR =
                            new games.cafecito.foundry.runtime.FoundryModuleDescriptor(
                                    2,
                                    "%s",
                                    "%s",
                                    "%s",
                                    "%s",
                                    "%s",
                                    "%s",
                                    java.util.List.of(
                %s));

                    @Override
                    public games.cafecito.foundry.runtime.FoundryModuleDescriptor descriptor() {
                        return DESCRIPTOR;
                    }
                }
                """
                .formatted(
                        packageName,
                        PROCESSOR,
                        className,
                        className,
                        className,
                        decoder,
                        moduleName,
                        qualifiedName,
                        provenance.apiSha256(),
                        provenance.generatorVersion(),
                        provenance.runtimeContractVersion(),
                        provenance.bridgeContractVersion(),
                        classes);
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
                                            new games.cafecito.foundry.runtime.FoundryClassDescriptor(
                                                    "%s",
                                                    "%s",
                                                    "%s",
                                                    "%s",
                                                    java.util.List.of(%s),
                                                    new games.cafecito.foundry.runtime.FoundryExtensionAccess() {
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

    private String descriptor(
            String moduleName,
            String registryName,
            Provenance provenance,
            List<ExtensionModel> models) {
        StringBuilder descriptor =
                new StringBuilder()
                        .append("format=2\n")
                        .append("module=")
                        .append(moduleName)
                        .append('\n')
                        .append("registry=")
                        .append(registryName)
                        .append('\n')
                        .append("api_sha256=")
                        .append(provenance.apiSha256())
                        .append('\n')
                        .append("generator_version=")
                        .append(provenance.generatorVersion())
                        .append('\n')
                        .append("runtime_contract_version=")
                        .append(provenance.runtimeContractVersion())
                        .append('\n')
                        .append("bridge_contract_version=")
                        .append(provenance.bridgeContractVersion())
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
                        .append(member.signature());
                if (member.details() != null) {
                    descriptor.append('|').append(member.details().descriptorFields());
                }
                descriptor.append('\n');
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
                        .append(" {\n")
                        .append(
                                "    public static final games.cafecito.foundry.runtime.FoundryModuleProvider PROVIDER;\n")
                        .append(
                                "    public games.cafecito.foundry.runtime.FoundryModuleDescriptor descriptor();\n")
                        .append("}\n");
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
        model.constants()
                .forEach(
                        constant ->
                                members.add(
                                        new Member(
                                                "constant",
                                                constant.exportedName(),
                                                constant.fieldName(),
                                                constant.type(),
                                                new ConstantMemberDetails(
                                                        constant.enumName(),
                                                        constant.value(),
                                                        constant.bitfield()))));
        model.properties()
                .forEach(
                        property ->
                                members.add(
                                        new Member(
                                                "property",
                                                property.exportedName(),
                                                property.fieldName(),
                                                property.type(),
                                                new PropertyMemberDetails(
                                                        property.getter(),
                                                        property.setter(),
                                                        property.index(),
                                                        property.groupName(),
                                                        property.groupPrefix(),
                                                        property.subgroupName(),
                                                        property.subgroupPrefix()))));
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
                        Comparator.comparingInt((Member member) -> memberRank(member.kind()))
                                .thenComparing(Member::foundryName)
                                .thenComparing(Member::javaName)
                                .thenComparing(Member::signature)
                                .thenComparing(
                                        member ->
                                                member.details() == null
                                                        ? ""
                                                        : member.details().descriptorFields()))
                .toList();
    }

    private static int memberRank(String kind) {
        return switch (kind) {
            case "constant" -> 0;
            case "method" -> 1;
            case "override" -> 2;
            case "property" -> 3;
            case "signal" -> 4;
            default -> throw new IllegalArgumentException("unknown member kind " + kind);
        };
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
        if (member.details() != null) {
            return indentation
                    + "new games.cafecito.foundry.runtime.FoundryMemberDescriptor(\n"
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
                    + "\",\n"
                    + continuation
                    + detailsExpression(member.details(), continuation)
                    + ")";
        }
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
            return indentation
                    + "new games.cafecito.foundry.runtime.FoundryMemberDescriptor(\n"
                    + continuation
                    + arguments;
        }
        return indentation
                + "new games.cafecito.foundry.runtime.FoundryMemberDescriptor(\n"
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

    private String detailsExpression(MemberDetails details, String continuation) {
        String nested = continuation + "        ";
        if (details instanceof ConstantMemberDetails constant) {
            return "new games.cafecito.foundry.runtime.FoundryConstantDetails(\n"
                    + nested
                    + "decode(\""
                    + encode(constant.enumName())
                    + "\"),\n"
                    + nested
                    + longLiteral(constant.value())
                    + ",\n"
                    + nested
                    + constant.bitfield()
                    + ")";
        }
        PropertyMemberDetails property = (PropertyMemberDetails) details;
        return "new games.cafecito.foundry.runtime.FoundryPropertyDetails(\n"
                + nested
                + "decode(\""
                + encode(property.getter())
                + "\"),\n"
                + nested
                + "decode(\""
                + encode(property.setter())
                + "\"),\n"
                + nested
                + property.index()
                + ",\n"
                + nested
                + "decode(\""
                + encode(property.groupName())
                + "\"),\n"
                + nested
                + "decode(\""
                + encode(property.groupPrefix())
                + "\"),\n"
                + nested
                + "decode(\""
                + encode(property.subgroupName())
                + "\"),\n"
                + nested
                + "decode(\""
                + encode(property.subgroupPrefix())
                + "\"))";
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String longLiteral(long value) {
        return value == Long.MIN_VALUE ? "Long.MIN_VALUE" : value + "L";
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

    private Provenance provenance() throws IOException {
        TypeElement runtime = elements.getTypeElement(RUNTIME);
        if (runtime == null) {
            throw new IOException("required runtime contract type is unavailable: " + RUNTIME);
        }
        return new Provenance(
                constant(runtime, "API_SHA256"),
                constant(runtime, "GENERATOR_VERSION"),
                constant(runtime, "RUNTIME_CONTRACT_VERSION"),
                constant(runtime, "BRIDGE_CONTRACT_VERSION"));
    }

    private String constant(TypeElement type, String name) throws IOException {
        for (var element : type.getEnclosedElements()) {
            if (element instanceof VariableElement field
                    && field.getSimpleName().contentEquals(name)
                    && field.getConstantValue() instanceof String value
                    && !value.isBlank()) {
                return value;
            }
        }
        throw new IOException("required runtime contract constant is unavailable: " + name);
    }

    private record Provenance(
            String apiSha256,
            String generatorVersion,
            String runtimeContractVersion,
            String bridgeContractVersion) {}

    private sealed interface MemberDetails permits ConstantMemberDetails, PropertyMemberDetails {
        String descriptorFields();
    }

    private record ConstantMemberDetails(String enumName, long value, boolean bitfield)
            implements MemberDetails {
        @Override
        public String descriptorFields() {
            return "d1|" + encode(enumName) + "|" + value + "|" + (bitfield ? "1" : "0");
        }
    }

    private record PropertyMemberDetails(
            String getter,
            String setter,
            int index,
            String groupName,
            String groupPrefix,
            String subgroupName,
            String subgroupPrefix)
            implements MemberDetails {
        @Override
        public String descriptorFields() {
            return "d1|"
                    + encode(getter)
                    + "|"
                    + encode(setter)
                    + "|"
                    + index
                    + "|"
                    + encode(groupName)
                    + "|"
                    + encode(groupPrefix)
                    + "|"
                    + encode(subgroupName)
                    + "|"
                    + encode(subgroupPrefix);
        }
    }

    private record Member(
            String kind,
            String foundryName,
            String javaName,
            String signature,
            MemberDetails details) {
        Member(String kind, String foundryName, String javaName, String signature) {
            this(kind, foundryName, javaName, signature, null);
        }
    }
}
