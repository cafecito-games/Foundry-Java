package games.cafecito.foundry.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import javax.annotation.processing.Filer;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;

final class SourceEmitter {
    private static final String PROCESSOR =
            "games.cafecito.foundry.processor.FoundryExtensionProcessor";
    private final Filer filer;

    SourceEmitter(Filer filer) {
        this.filer = filer;
    }

    ReservedTrampoline reserveTrampoline(ExtensionModel model, TypeElement source)
            throws IOException {
        String qualifiedName =
                model.packageName().isEmpty()
                        ? model.simpleName() + "_FoundryTrampoline"
                        : model.packageName() + "." + model.simpleName() + "_FoundryTrampoline";
        return new ReservedTrampoline(model, filer.createSourceFile(qualifiedName, source));
    }

    void emitTrampoline(ReservedTrampoline reserved) throws IOException {
        try (Writer writer = reserved.source().openWriter()) {
            writer.write(trampolineSource(reserved.model()));
        }
    }

    record ReservedTrampoline(ExtensionModel model, JavaFileObject source) {}

    private String trampolineSource(ExtensionModel model) {
        List<EnumBinding> enumBindings = enumBindings(model);
        StringBuilder source = new StringBuilder();
        if (!model.packageName().isEmpty()) {
            source.append("package ").append(model.packageName()).append(";\n\n");
        }
        String trampoline = model.simpleName() + "_FoundryTrampoline";
        source.append("@javax.annotation.processing.Generated(\"")
                .append(PROCESSOR)
                .append("\")\n")
                .append("public final class ")
                .append(trampoline)
                .append(" {\n")
                .append("    private ")
                .append(trampoline)
                .append("() {}\n\n")
                .append("    public static ")
                .append(model.simpleName())
                .append(
                        model.bindingConstructor()
                                ? bindingConstructSignature()
                                : " construct() {\n")
                .append("        return new ")
                .append(model.simpleName())
                .append(model.bindingConstructor() ? "(context, lease);\n" : "();\n")
                .append("    }\n\n");
        appendEnumHelpers(source, enumBindings);
        appendInvoke(source, model, enumBindings);
        source.append('\n');
        appendPropertyGetter(source, model, enumBindings);
        source.append('\n');
        appendPropertySetter(source, model, enumBindings);
        source.append("}\n");
        return source.toString();
    }

    private String bindingConstructSignature() {
        return " construct(\n"
                + "            games.cafecito.foundry.runtime.FoundryBindingContext context,\n"
                + "            games.cafecito.foundry.runtime.ObjectLease lease) {\n";
    }

    private void appendEnumHelpers(StringBuilder source, List<EnumBinding> enumBindings) {
        for (EnumBinding binding : enumBindings) {
            appendEnumInboundHelper(source, binding);
            source.append('\n');
        }
        for (EnumBinding binding : enumBindings) {
            appendEnumOutboundHelper(source, binding);
            source.append('\n');
        }
    }

    private void appendEnumInboundHelper(StringBuilder source, EnumBinding binding) {
        ExtensionModel.EnumModel enumModel = binding.model();
        source.append("    private static ")
                .append(enumModel.qualifiedName())
                .append(" enumInbound")
                .append(binding.index())
                .append("(Object value) {\n")
                .append("        if (!(value instanceof Long boxed)) {\n")
                .append(
                        "            throw new IllegalArgumentException(\"Expected boxed Long for enum ")
                .append(enumModel.qualifiedName())
                .append("\");\n")
                .append("        }\n")
                .append("        long numeric = boxed.longValue();\n");
        if (enumModel.origin() == ExtensionModel.EnumOrigin.GENERATED) {
            source.append("        try {\n")
                    .append("            ")
                    .append(enumModel.qualifiedName())
                    .append(" converted = ")
                    .append(enumModel.qualifiedName())
                    .append(".fromValue(numeric);\n")
                    .append("            if (converted == null) {\n")
                    .append("                throw new IllegalArgumentException();\n")
                    .append("            }\n")
                    .append("            return converted;\n")
                    .append("        } catch (IllegalArgumentException failure) {\n")
                    .append(
                            "            throw new IllegalArgumentException(\"Unknown enum value \" + numeric + \" for ")
                    .append(enumModel.qualifiedName())
                    .append("\", failure);\n")
                    .append("        }\n");
        } else {
            for (ExtensionModel.EnumConstantModel constant : enumModel.constants()) {
                source.append("        if (numeric == ")
                        .append(longLiteral(constant.value()))
                        .append(") {\n")
                        .append("            return ")
                        .append(enumModel.qualifiedName())
                        .append('.')
                        .append(constant.javaName())
                        .append(";\n")
                        .append("        }\n");
            }
            source.append(
                            "        throw new IllegalArgumentException(\"Unknown enum value \" + numeric + \" for ")
                    .append(enumModel.qualifiedName())
                    .append("\");\n");
        }
        source.append("    }\n");
    }

    private void appendEnumOutboundHelper(StringBuilder source, EnumBinding binding) {
        ExtensionModel.EnumModel enumModel = binding.model();
        source.append("    private static Long enumOutbound")
                .append(binding.index())
                .append('(')
                .append(enumModel.qualifiedName())
                .append(" value) {\n")
                .append("        if (value == null) {\n")
                .append("            throw new IllegalArgumentException(\"Cannot encode null enum ")
                .append(enumModel.qualifiedName())
                .append("\");\n")
                .append("        }\n");
        if (enumModel.origin() == ExtensionModel.EnumOrigin.GENERATED) {
            source.append("        return Long.valueOf(value.value());\n");
        } else {
            for (ExtensionModel.EnumConstantModel constant : enumModel.constants()) {
                source.append("        if (value == ")
                        .append(enumModel.qualifiedName())
                        .append('.')
                        .append(constant.javaName())
                        .append(") {\n")
                        .append("            return Long.valueOf(")
                        .append(longLiteral(constant.value()))
                        .append(");\n")
                        .append("        }\n");
            }
            source.append(
                            "        throw new IllegalArgumentException(\"Unmapped enum constant for ")
                    .append(enumModel.qualifiedName())
                    .append("\");\n");
        }
        source.append("    }\n");
    }

    private void appendInvoke(
            StringBuilder source, ExtensionModel model, List<EnumBinding> enumBindings) {
        if (model.methods().isEmpty() && model.overrides().isEmpty()) {
            source.append(
                            "    public static Object invoke(Object target, String name, Object[] arguments) {\n")
                    .append(
                            "        throw new IllegalArgumentException(\"Unknown method: \" + name);\n")
                    .append("    }\n");
            return;
        }
        source.append(
                        "    public static Object invoke(Object target, String name, Object[] arguments) {\n")
                .append("        ")
                .append(model.simpleName())
                .append(" receiver = (")
                .append(model.simpleName())
                .append(") target;\n")
                .append("        return switch (name) {\n");
        List<ExtensionModel.MethodModel> methods = new ArrayList<>();
        methods.addAll(model.methods());
        methods.addAll(model.overrides());
        methods.sort(ExtensionModel.MethodModel.ORDER);
        for (ExtensionModel.MethodModel method : methods) {
            source.append("            case \"").append(method.javaName()).append("\" -> {\n");
            String invocation =
                    "receiver."
                            + method.javaName()
                            + "("
                            + arguments(model, method.parameters(), "arguments", enumBindings)
                            + ")";
            if (method.returnType().equals("void")) {
                source.append("                ")
                        .append(invocation)
                        .append(";\n")
                        .append("                yield null;\n");
            } else {
                source.append("                yield ")
                        .append(outbound(model, method.returnType(), invocation, enumBindings))
                        .append(";\n");
            }
            source.append("            }\n");
        }
        source.append(
                        "            default -> throw new IllegalArgumentException(\"Unknown method: \" + name);\n")
                .append("        };\n")
                .append("    }\n");
    }

    private void appendPropertyGetter(
            StringBuilder source, ExtensionModel model, List<EnumBinding> enumBindings) {
        if (model.properties().isEmpty()) {
            source.append("    public static Object getProperty(Object target, String name) {\n")
                    .append(
                            "        throw new IllegalArgumentException(\"Unknown property: \" + name);\n")
                    .append("    }\n");
            return;
        }
        source.append("    public static Object getProperty(Object target, String name) {\n")
                .append("        ")
                .append(model.simpleName())
                .append(" receiver = (")
                .append(model.simpleName())
                .append(") target;\n")
                .append("        return switch (name) {\n");
        for (ExtensionModel.PropertyModel property : model.properties()) {
            String invocation = "receiver." + property.getter() + "()";
            source.append("            case \"")
                    .append(property.getter())
                    .append("\" -> ")
                    .append(outbound(model, property.type(), invocation, enumBindings))
                    .append(";\n");
        }
        source.append(
                        "            default -> throw new IllegalArgumentException(\"Unknown property: \" + name);\n")
                .append("        };\n")
                .append("    }\n");
    }

    private void appendPropertySetter(
            StringBuilder source, ExtensionModel model, List<EnumBinding> enumBindings) {
        source.append(
                        "    public static void setProperty(Object target, String name, Object value) {\n")
                .append("        ")
                .append(model.simpleName())
                .append(" receiver = (")
                .append(model.simpleName())
                .append(") target;\n")
                .append("        switch (name) {\n");
        model.properties().stream()
                .filter(property -> !property.setter().isEmpty())
                .forEach(
                        property ->
                                source.append("            case \"")
                                        .append(property.setter())
                                        .append("\" -> receiver.")
                                        .append(property.setter())
                                        .append('(')
                                        .append(
                                                inbound(
                                                        model,
                                                        property.type(),
                                                        "value",
                                                        enumBindings))
                                        .append(");\n"));
        source.append(
                        "            default -> throw new IllegalArgumentException(\"Unknown property: \" + name);\n")
                .append("        }\n")
                .append("    }\n");
    }

    private String arguments(
            ExtensionModel model,
            List<ExtensionModel.ParameterModel> parameters,
            String arrayName,
            List<EnumBinding> enumBindings) {
        List<String> arguments = new ArrayList<>();
        for (int index = 0; index < parameters.size(); index++) {
            arguments.add(
                    inbound(
                            model,
                            parameters.get(index).type(),
                            arrayName + "[" + index + "]",
                            enumBindings));
        }
        return String.join(", ", arguments);
    }

    private String inbound(
            ExtensionModel model, String javaType, String value, List<EnumBinding> enumBindings) {
        return enumBinding(model, javaType, enumBindings)
                .map(binding -> "enumInbound" + binding.index() + "(" + value + ")")
                .orElse("(" + javaType + ") " + value);
    }

    private String outbound(
            ExtensionModel model, String javaType, String value, List<EnumBinding> enumBindings) {
        return enumBinding(model, javaType, enumBindings)
                .map(binding -> "enumOutbound" + binding.index() + "(" + value + ")")
                .orElse(value);
    }

    private java.util.Optional<EnumBinding> enumBinding(
            ExtensionModel model, String javaType, List<EnumBinding> enumBindings) {
        return model.enumModel(javaType)
                .flatMap(
                        enumModel ->
                                enumBindings.stream()
                                        .filter(
                                                binding ->
                                                        binding.model()
                                                                .qualifiedName()
                                                                .equals(enumModel.qualifiedName()))
                                        .findFirst());
    }

    private List<EnumBinding> enumBindings(ExtensionModel model) {
        TreeMap<String, ExtensionModel.EnumModel> usedEnums = new TreeMap<>();
        List<ExtensionModel.MethodModel> methods = new ArrayList<>();
        methods.addAll(model.methods());
        methods.addAll(model.overrides());
        for (ExtensionModel.MethodModel method : methods) {
            addEnum(model, usedEnums, method.returnType());
            for (ExtensionModel.ParameterModel parameter : method.parameters()) {
                addEnum(model, usedEnums, parameter.type());
            }
        }
        for (ExtensionModel.PropertyModel property : model.properties()) {
            addEnum(model, usedEnums, property.type());
        }
        List<EnumBinding> bindings = new ArrayList<>();
        int index = 0;
        for (ExtensionModel.EnumModel enumModel : usedEnums.values()) {
            bindings.add(new EnumBinding(enumModel, index++));
        }
        return List.copyOf(bindings);
    }

    private void addEnum(
            ExtensionModel model,
            TreeMap<String, ExtensionModel.EnumModel> usedEnums,
            String javaType) {
        model.enumModel(javaType)
                .ifPresent(enumModel -> usedEnums.put(enumModel.qualifiedName(), enumModel));
    }

    private String longLiteral(long value) {
        if (value == Long.MIN_VALUE) {
            return "Long.MIN_VALUE";
        }
        if (value == Long.MAX_VALUE) {
            return "Long.MAX_VALUE";
        }
        return value + "L";
    }

    private record EnumBinding(ExtensionModel.EnumModel model, int index) {}
}
