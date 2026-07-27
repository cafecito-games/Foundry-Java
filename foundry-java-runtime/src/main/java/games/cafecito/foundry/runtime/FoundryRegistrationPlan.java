package games.cafecito.foundry.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/** Immutable, whole-bootstrap registration order validated before native mutation. */
public final class FoundryRegistrationPlan {
    private static final Comparator<ClassEntry> ORDER =
            Comparator.comparingInt((ClassEntry entry) -> entry.level().code())
                    .thenComparing(entry -> entry.descriptor().javaName())
                    .thenComparing(entry -> entry.descriptor().foundryName())
                    .thenComparing(ClassEntry::module)
                    .thenComparing(ClassEntry::registry);

    private final List<ClassEntry> entries;
    private final Map<FoundryInitializationLevel, List<FoundryClassDescriptor>> byLevel;

    private FoundryRegistrationPlan(List<ClassEntry> entries) {
        this.entries = List.copyOf(entries);
        EnumMap<FoundryInitializationLevel, List<FoundryClassDescriptor>> grouped =
                new EnumMap<>(FoundryInitializationLevel.class);
        for (FoundryInitializationLevel level : FoundryInitializationLevel.values()) {
            grouped.put(
                    level,
                    entries.stream()
                            .filter(entry -> entry.level() == level)
                            .map(ClassEntry::descriptor)
                            .toList());
        }
        byLevel = Map.copyOf(grouped);
    }

    public static FoundryRegistrationPlan create(FoundryRegistryBootstrap bootstrap) {
        Objects.requireNonNull(bootstrap, "bootstrap");
        Map<String, ClassEntry> byJavaName = new HashMap<>();
        Set<String> foundryNames = new HashSet<>();
        for (FoundryModuleDescriptor module : bootstrap.descriptors()) {
            for (FoundryClassDescriptor descriptor : module.classes()) {
                FoundryInitializationLevel level = parseLevel(module.module(), descriptor);
                ClassEntry entry =
                        new ClassEntry(module.module(), module.registry(), descriptor, level);
                ClassEntry previous = byJavaName.putIfAbsent(descriptor.javaName(), entry);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "Duplicate Foundry Java class identity "
                                    + descriptor.javaName()
                                    + " in modules "
                                    + previous.module()
                                    + " and "
                                    + module.module()
                                    + ".");
                }
                if (!foundryNames.add(descriptor.foundryName())) {
                    throw new IllegalArgumentException(
                            "Duplicate Foundry class identity " + descriptor.foundryName() + ".");
                }
            }
        }

        Map<ClassEntry, Integer> indegrees = new HashMap<>();
        Map<ClassEntry, List<ClassEntry>> dependents = new HashMap<>();
        byJavaName.values().forEach(entry -> indegrees.put(entry, 0));
        for (ClassEntry entry : byJavaName.values()) {
            Set<String> uniqueDependencies = new HashSet<>();
            for (String dependencyName : entry.descriptor().after()) {
                if (!uniqueDependencies.add(dependencyName)) {
                    throw new IllegalArgumentException(
                            "Foundry class "
                                    + entry.descriptor().javaName()
                                    + " repeats dependency "
                                    + dependencyName
                                    + ".");
                }
                ClassEntry dependency = byJavaName.get(dependencyName);
                if (dependency == null) {
                    throw new IllegalArgumentException(
                            "Foundry class "
                                    + entry.descriptor().javaName()
                                    + " has missing qualified dependency "
                                    + dependencyName
                                    + ".");
                }
                if (dependency.level().code() > entry.level().code()) {
                    throw new IllegalArgumentException(
                            "Foundry class "
                                    + entry.descriptor().javaName()
                                    + " at "
                                    + entry.level()
                                    + " depends on later-level "
                                    + dependencyName
                                    + " at "
                                    + dependency.level()
                                    + ".");
                }
                dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(entry);
                indegrees.compute(entry, (ignored, count) -> Objects.requireNonNull(count) + 1);
            }
        }

        PriorityQueue<ClassEntry> ready = new PriorityQueue<>(ORDER);
        indegrees.forEach(
                (entry, degree) -> {
                    if (degree == 0) {
                        ready.add(entry);
                    }
                });
        List<ClassEntry> ordered = new ArrayList<>(indegrees.size());
        while (!ready.isEmpty()) {
            ClassEntry entry = ready.remove();
            ordered.add(entry);
            for (ClassEntry dependent : dependents.getOrDefault(entry, List.of())) {
                int remaining =
                        indegrees.compute(
                                dependent, (ignored, degree) -> Objects.requireNonNull(degree) - 1);
                if (remaining == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (ordered.size() != indegrees.size()) {
            List<String> cycle =
                    indegrees.entrySet().stream()
                            .filter(entry -> entry.getValue() != 0)
                            .map(entry -> entry.getKey().descriptor().javaName())
                            .sorted()
                            .toList();
            throw new IllegalArgumentException(
                    "Foundry class dependency cycle contains " + cycle + ".");
        }
        return new FoundryRegistrationPlan(ordered);
    }

    public List<FoundryClassDescriptor> orderedClasses() {
        return entries.stream().map(ClassEntry::descriptor).toList();
    }

    public List<FoundryClassDescriptor> classes(FoundryInitializationLevel level) {
        return byLevel.get(Objects.requireNonNull(level, "level"));
    }

    private static FoundryInitializationLevel parseLevel(
            String module, FoundryClassDescriptor descriptor) {
        try {
            return FoundryInitializationLevel.valueOf(descriptor.initializationLevel());
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "Foundry class "
                            + descriptor.javaName()
                            + " in module "
                            + module
                            + " uses unknown initialization level "
                            + descriptor.initializationLevel()
                            + ".",
                    failure);
        }
    }

    private record ClassEntry(
            String module,
            String registry,
            FoundryClassDescriptor descriptor,
            FoundryInitializationLevel level) {}
}
