package games.cafecito.foundry.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Filer;
import javax.lang.model.element.TypeElement;

final class SourceEmitter {
    private static final String PROCESSOR =
            "games.cafecito.foundry.processor.FoundryExtensionProcessor";
    private final Filer filer;

    SourceEmitter(Filer filer) {
        this.filer = filer;
    }

    void emitTrampoline(ExtensionModel model, TypeElement source) throws IOException {
        String qualifiedName =
                model.packageName().isEmpty()
                        ? model.simpleName() + "_FoundryTrampoline"
                        : model.packageName() + "." + model.simpleName() + "_FoundryTrampoline";
        try (Writer writer = filer.createSourceFile(qualifiedName, source).openWriter()) {
            writer.write(trampolineSource(model));
        }
    }

    private String trampolineSource(ExtensionModel model) {
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
                .append(" construct() {\n")
                .append("        return new ")
                .append(model.simpleName())
                .append("();\n")
                .append("    }\n\n");
        appendInvoke(source, model);
        source.append('\n');
        appendPropertyGetter(source, model);
        source.append('\n');
        appendPropertySetter(source, model);
        source.append("}\n");
        return source.toString();
    }

    private void appendInvoke(StringBuilder source, ExtensionModel model) {
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
            source.append("            case \"").append(method.exportedName()).append("\" -> {\n");
            String invocation =
                    "receiver."
                            + method.javaName()
                            + "("
                            + arguments(method.parameters(), "arguments")
                            + ")";
            if (method.returnType().equals("void")) {
                source.append("                ")
                        .append(invocation)
                        .append(";\n")
                        .append("                yield null;\n");
            } else {
                source.append("                yield ").append(invocation).append(";\n");
            }
            source.append("            }\n");
        }
        source.append(
                        "            default -> throw new IllegalArgumentException(\"Unknown method: \" + name);\n")
                .append("        };\n")
                .append("    }\n");
    }

    private void appendPropertyGetter(StringBuilder source, ExtensionModel model) {
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
            source.append("            case \"")
                    .append(property.exportedName())
                    .append("\" -> receiver.")
                    .append(property.getter())
                    .append("();\n");
        }
        source.append(
                        "            default -> throw new IllegalArgumentException(\"Unknown property: \" + name);\n")
                .append("        };\n")
                .append("    }\n");
    }

    private void appendPropertySetter(StringBuilder source, ExtensionModel model) {
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
                                        .append(property.exportedName())
                                        .append("\" -> receiver.")
                                        .append(property.setter())
                                        .append("((")
                                        .append(property.type())
                                        .append(") value);\n"));
        source.append(
                        "            default -> throw new IllegalArgumentException(\"Unknown property: \" + name);\n")
                .append("        }\n")
                .append("    }\n");
    }

    private String arguments(List<ExtensionModel.ParameterModel> parameters, String arrayName) {
        List<String> arguments = new ArrayList<>();
        for (int index = 0; index < parameters.size(); index++) {
            arguments.add(
                    "(" + parameters.get(index).type() + ") " + arrayName + "[" + index + "]");
        }
        return String.join(", ", arguments);
    }
}
