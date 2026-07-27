package games.cafecito.foundry.processor;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

/** Validates Java extension declarations and generates reflection-free registration artifacts. */
public final class FoundryExtensionProcessor extends AbstractProcessor {
    private static final String MODULE_NAME_PATTERN = "[a-z][a-z0-9]*(?:-[a-z][a-z0-9]*)*";
    private final Map<String, ExtensionModel> models = new LinkedHashMap<>();
    private final Map<String, TypeElement> sourceElements = new LinkedHashMap<>();
    // JSR-269 source identities are single-assignment; Filer rejects recreating the same FQN.
    private final Map<String, SourceEmitter.ReservedTrampoline> reservedTrampolines =
            new LinkedHashMap<>();
    private final Set<String> reportedModuleNameConflicts = new LinkedHashSet<>();
    private ExtensionValidator validator;
    private SourceEmitter sourceEmitter;
    private ModuleEmitter moduleEmitter;
    private ModuleEmitter.ReservedRegistry reservedRegistry;
    private int moduleErrorCount;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(
                ExtensionValidator.CLASS,
                ExtensionValidator.CONSTANT,
                ExtensionValidator.ENUM_VALUE,
                ExtensionValidator.METHOD,
                ExtensionValidator.PROPERTY,
                ExtensionValidator.SIGNAL,
                ExtensionValidator.OVERRIDE,
                ExtensionValidator.INITIALIZATION);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.RELEASE_17;
    }

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of("foundry.module");
    }

    @Override
    public boolean process(
            Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        if (validator == null) {
            validator = new ExtensionValidator(processingEnv);
            sourceEmitter = new SourceEmitter(processingEnv.getFiler());
            moduleEmitter =
                    new ModuleEmitter(processingEnv.getFiler(), processingEnv.getElementUtils());
        }
        TypeElement foundryClass =
                processingEnv.getElementUtils().getTypeElement(ExtensionValidator.CLASS);
        int discovered = 0;
        if (foundryClass != null) {
            for (Element element : roundEnvironment.getElementsAnnotatedWith(foundryClass)) {
                if (element instanceof TypeElement type) {
                    discovered++;
                    validator
                            .validate(type)
                            .ifPresent(
                                    model -> {
                                        models.put(model.qualifiedName(), model);
                                        sourceElements.put(model.qualifiedName(), type);
                                        if (!reservedTrampolines.containsKey(
                                                model.qualifiedName())) {
                                            try {
                                                reservedTrampolines.put(
                                                        model.qualifiedName(),
                                                        sourceEmitter.reserveTrampoline(
                                                                model, type));
                                                reserveModuleRegistry(type);
                                            } catch (IOException exception) {
                                                moduleErrorCount++;
                                                processingEnv
                                                        .getMessager()
                                                        .printMessage(
                                                                javax.tools.Diagnostic.Kind.ERROR,
                                                                "cannot generate trampoline: "
                                                                        + exception.getMessage(),
                                                                type);
                                            }
                                        }
                                    });
                } else {
                    moduleErrorCount++;
                    processingEnv
                            .getMessager()
                            .printMessage(
                                    javax.tools.Diagnostic.Kind.ERROR,
                                    "@FoundryClass may only annotate a type",
                                    element);
                }
            }
        }
        validator.validateAnnotationPlacement(roundEnvironment);
        if (discovered > 0) {
            validateModuleNames();
        }
        if (roundEnvironment.processingOver() && !models.isEmpty()) {
            validator.validateCycles(Map.copyOf(models), Map.copyOf(sourceElements));
            validateModuleNames();
            validateModuleOption();
            if (validator.errorCount() == 0 && moduleErrorCount == 0) {
                emitTrampolines();
                if (moduleErrorCount == 0) {
                    emitModule();
                }
            }
        }
        return true;
    }

    private void validateModuleNames() {
        Map<String, String> exported = new LinkedHashMap<>();
        models.values().stream()
                .sorted(java.util.Comparator.comparing(ExtensionModel::qualifiedName))
                .forEach(
                        model -> {
                            String previous =
                                    exported.putIfAbsent(
                                            model.exportedName(), model.qualifiedName());
                            String conflict =
                                    model.exportedName()
                                            + "|"
                                            + previous
                                            + "|"
                                            + model.qualifiedName();
                            if (previous != null && reportedModuleNameConflicts.add(conflict)) {
                                moduleErrorCount++;
                                processingEnv
                                        .getMessager()
                                        .printMessage(
                                                javax.tools.Diagnostic.Kind.ERROR,
                                                "duplicate exported class name "
                                                        + model.exportedName()
                                                        + " from "
                                                        + previous,
                                                sourceElements.get(model.qualifiedName()));
                            }
                        });
    }

    private void validateModuleOption() {
        String moduleName = processingEnv.getOptions().get("foundry.module");
        if (moduleName == null || !moduleName.matches(MODULE_NAME_PATTERN)) {
            moduleErrorCount++;
            processingEnv
                    .getMessager()
                    .printMessage(
                            javax.tools.Diagnostic.Kind.ERROR,
                            "processor option -Afoundry.module must be a stable lowercase name");
            return;
        }
        if (SourceVersion.isKeyword(moduleName.replace("-", ""), SourceVersion.RELEASE_17)) {
            moduleErrorCount++;
            processingEnv
                    .getMessager()
                    .printMessage(
                            javax.tools.Diagnostic.Kind.ERROR,
                            "processor option -Afoundry.module cannot produce a Java keyword package");
        }
    }

    private void emitModule() {
        if (reservedRegistry == null) {
            moduleErrorCount++;
            processingEnv
                    .getMessager()
                    .printMessage(
                            javax.tools.Diagnostic.Kind.ERROR,
                            "cannot generate module registry: registry output was not reserved");
            return;
        }
        try {
            moduleEmitter.emit(
                    reservedRegistry,
                    List.copyOf(models.values()),
                    List.copyOf(sourceElements.values()));
        } catch (IOException exception) {
            moduleErrorCount++;
            processingEnv
                    .getMessager()
                    .printMessage(
                            javax.tools.Diagnostic.Kind.ERROR,
                            "cannot generate module registry: " + exception.getMessage());
        }
    }

    private void reserveModuleRegistry(TypeElement source) {
        if (reservedRegistry != null) {
            return;
        }
        String moduleName = processingEnv.getOptions().get("foundry.module");
        if (moduleName == null
                || !moduleName.matches(MODULE_NAME_PATTERN)
                || SourceVersion.isKeyword(moduleName.replace("-", ""), SourceVersion.RELEASE_17)) {
            return;
        }
        try {
            reservedRegistry = moduleEmitter.reserve(moduleName, source);
        } catch (IOException exception) {
            moduleErrorCount++;
            processingEnv
                    .getMessager()
                    .printMessage(
                            javax.tools.Diagnostic.Kind.ERROR,
                            "cannot reserve module registry: " + exception.getMessage(),
                            source);
        }
    }

    private void emitTrampolines() {
        reservedTrampolines.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        entry -> {
                            try {
                                sourceEmitter.emitTrampoline(entry.getValue());
                            } catch (IOException exception) {
                                moduleErrorCount++;
                                processingEnv
                                        .getMessager()
                                        .printMessage(
                                                javax.tools.Diagnostic.Kind.ERROR,
                                                "cannot generate trampoline: "
                                                        + exception.getMessage(),
                                                sourceElements.get(entry.getKey()));
                            }
                        });
    }
}
