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
    private final Map<String, ExtensionModel> models = new LinkedHashMap<>();
    private final Map<String, TypeElement> sourceElements = new LinkedHashMap<>();
    private final Set<String> emittedTrampolines = new LinkedHashSet<>();
    private final Set<String> generatedTypeNames = new LinkedHashSet<>();
    private final Set<String> reportedModuleNameConflicts = new LinkedHashSet<>();
    private ExtensionValidator validator;
    private SourceEmitter sourceEmitter;
    private boolean moduleEmitted;
    private int moduleErrorCount;
    private int emptyDiscoveryRounds;
    private int barrierSequence;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(
                ExtensionValidator.CLASS,
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
        }
        TypeElement foundryClass =
                processingEnv.getElementUtils().getTypeElement(ExtensionValidator.CLASS);
        int discovered = 0;
        if (foundryClass != null) {
            for (Element element : roundEnvironment.getElementsAnnotatedWith(foundryClass)) {
                if (element instanceof TypeElement type) {
                    discovered++;
                    int errorsBefore = validator.errorCount();
                    validator
                            .validate(type)
                            .ifPresent(
                                    model -> {
                                        models.put(model.qualifiedName(), model);
                                        sourceElements.put(model.qualifiedName(), type);
                                        if (validator.errorCount() == errorsBefore
                                                && !emittedTrampolines.contains(
                                                        model.qualifiedName())) {
                                            try {
                                                generatedTypeNames.add(
                                                        sourceEmitter.emitTrampoline(model, type));
                                                emittedTrampolines.add(model.qualifiedName());
                                            } catch (IOException exception) {
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
        boolean externalRoundActivity =
                roundEnvironment.getRootElements().stream()
                        .filter(TypeElement.class::isInstance)
                        .map(TypeElement.class::cast)
                        .map(type -> type.getQualifiedName().toString())
                        .anyMatch(
                                name ->
                                        !sourceElements.containsKey(name)
                                                && !generatedTypeNames.contains(name));
        if (discovered > 0) {
            emptyDiscoveryRounds = 0;
            validateModuleNames();
        } else if (externalRoundActivity) {
            emptyDiscoveryRounds = 0;
        }
        if (discovered == 0
                && !roundEnvironment.processingOver()
                && !models.isEmpty()
                && !moduleEmitted) {
            emptyDiscoveryRounds++;
            if (emptyDiscoveryRounds == 1) {
                emitRoundBarrier();
                return true;
            }
            moduleEmitted = true;
            validator.validateCycles(Map.copyOf(models), Map.copyOf(sourceElements));
            validateModuleNames();
            if (validator.errorCount() == 0 && moduleErrorCount == 0) {
                emitModule();
            }
        }
        return true;
    }

    private void emitRoundBarrier() {
        try {
            generatedTypeNames.add(sourceEmitter.emitRoundBarrier(++barrierSequence));
        } catch (IOException exception) {
            processingEnv
                    .getMessager()
                    .printMessage(
                            javax.tools.Diagnostic.Kind.ERROR,
                            "cannot defer module registry generation: " + exception.getMessage());
        }
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

    private void emitModule() {
        String moduleName = processingEnv.getOptions().get("foundry.module");
        if (moduleName == null || !moduleName.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")) {
            processingEnv
                    .getMessager()
                    .printMessage(
                            javax.tools.Diagnostic.Kind.ERROR,
                            "processor option -Afoundry.module must be a stable lowercase name");
            return;
        }
        try {
            new ModuleEmitter(processingEnv.getFiler())
                    .emit(
                            moduleName,
                            List.copyOf(models.values()),
                            List.copyOf(sourceElements.values()));
        } catch (IOException exception) {
            processingEnv
                    .getMessager()
                    .printMessage(
                            javax.tools.Diagnostic.Kind.ERROR,
                            "cannot generate module registry: " + exception.getMessage());
        }
    }
}
