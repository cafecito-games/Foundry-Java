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
    private static final Comparator<FoundryMemberDescriptor> MEMBER_ORDER =
            Comparator.comparing(FoundryMemberDescriptor::kind)
                    .thenComparing(FoundryMemberDescriptor::foundryName)
                    .thenComparing(FoundryMemberDescriptor::javaName)
                    .thenComparing(FoundryMemberDescriptor::signature)
                    .thenComparing(
                            FoundryMemberDescriptor::details,
                            FoundryRegistrationPlan::compareDetails);

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
                FoundryClassDescriptor normalized = normalizeMembers(module.module(), descriptor);
                FoundryInitializationLevel level = parseLevel(module.module(), normalized);
                ClassEntry entry =
                        new ClassEntry(module.module(), module.registry(), normalized, level);
                ClassEntry previous = byJavaName.putIfAbsent(normalized.javaName(), entry);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "Duplicate Foundry Java class identity "
                                    + normalized.javaName()
                                    + " in modules "
                                    + previous.module()
                                    + " and "
                                    + module.module()
                                    + ".");
                }
                if (!foundryNames.add(normalized.foundryName())) {
                    throw new IllegalArgumentException(
                            "Duplicate Foundry class identity " + normalized.foundryName() + ".");
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

    private static FoundryClassDescriptor normalizeMembers(
            String module, FoundryClassDescriptor descriptor) {
        Set<String> foundryNames = new HashSet<>();
        for (FoundryMemberDescriptor member : descriptor.members()) {
            validateKind(module, descriptor, member);
            if (!foundryNames.add(member.foundryName())) {
                throw new IllegalArgumentException(
                        "Foundry class "
                                + descriptor.javaName()
                                + " in module "
                                + module
                                + " repeats exported member "
                                + member.foundryName()
                                + ".");
            }
        }
        List<FoundryMemberDescriptor> members =
                descriptor.members().stream().sorted(MEMBER_ORDER).toList();
        return new FoundryClassDescriptor(
                descriptor.javaName(),
                descriptor.foundryName(),
                descriptor.baseName(),
                descriptor.initializationLevel(),
                descriptor.after(),
                descriptor.access(),
                members);
    }

    private static void validateKind(
            String module, FoundryClassDescriptor descriptor, FoundryMemberDescriptor member) {
        switch (member.kind()) {
            case "constant", "method", "override", "property", "signal" -> {
                return;
            }
            default ->
                    throw new IllegalArgumentException(
                            "Foundry class "
                                    + descriptor.javaName()
                                    + " in module "
                                    + module
                                    + " uses unknown member kind "
                                    + member.kind()
                                    + ".");
        }
    }

    private static int compareDetails(FoundryMemberDetails left, FoundryMemberDetails right) {
        int rank = Integer.compare(detailsRank(left), detailsRank(right));
        if (rank != 0) {
            return rank;
        }
        if (left instanceof FoundryConstantDetails leftConstant
                && right instanceof FoundryConstantDetails rightConstant) {
            int comparison = leftConstant.enumName().compareTo(rightConstant.enumName());
            if (comparison != 0) {
                return comparison;
            }
            comparison = Long.compare(leftConstant.value(), rightConstant.value());
            if (comparison != 0) {
                return comparison;
            }
            return Boolean.compare(leftConstant.bitfield(), rightConstant.bitfield());
        }
        if (left instanceof FoundryPropertyDetails leftProperty
                && right instanceof FoundryPropertyDetails rightProperty) {
            int comparison = leftProperty.getter().compareTo(rightProperty.getter());
            if (comparison != 0) {
                return comparison;
            }
            comparison = leftProperty.setter().compareTo(rightProperty.setter());
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(leftProperty.index(), rightProperty.index());
            if (comparison != 0) {
                return comparison;
            }
            comparison = leftProperty.groupName().compareTo(rightProperty.groupName());
            if (comparison != 0) {
                return comparison;
            }
            comparison = leftProperty.groupPrefix().compareTo(rightProperty.groupPrefix());
            if (comparison != 0) {
                return comparison;
            }
            comparison = leftProperty.subgroupName().compareTo(rightProperty.subgroupName());
            if (comparison != 0) {
                return comparison;
            }
            return leftProperty.subgroupPrefix().compareTo(rightProperty.subgroupPrefix());
        }
        return 0;
    }

    private static int detailsRank(FoundryMemberDetails details) {
        if (details == FoundryMemberDetails.none()) {
            return 0;
        }
        if (details instanceof FoundryConstantDetails) {
            return 1;
        }
        if (details instanceof FoundryPropertyDetails) {
            return 2;
        }
        throw new IllegalArgumentException(
                "Unsupported Foundry member details " + details.getClass().getName() + ".");
    }

    private record ClassEntry(
            String module,
            String registry,
            FoundryClassDescriptor descriptor,
            FoundryInitializationLevel level) {}
}
