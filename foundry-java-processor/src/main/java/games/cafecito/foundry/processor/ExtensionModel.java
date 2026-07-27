package games.cafecito.foundry.processor;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

record ExtensionModel(
        String qualifiedName,
        String packageName,
        String simpleName,
        String exportedName,
        String baseType,
        String initializationLevel,
        boolean bindingConstructor,
        List<String> initializationDependencies,
        List<MethodModel> methods,
        List<ConstantModel> constants,
        List<PropertyModel> properties,
        List<SignalModel> signals,
        List<MethodModel> overrides,
        List<EnumModel> enums) {
    ExtensionModel {
        initializationDependencies = initializationDependencies.stream().sorted().toList();
        methods = methods.stream().sorted(MethodModel.ORDER).toList();
        constants = constants.stream().sorted(ConstantModel.ORDER).toList();
        properties = properties.stream().sorted(PropertyModel.ORDER).toList();
        signals = signals.stream().sorted(SignalModel.ORDER).toList();
        overrides = overrides.stream().sorted(MethodModel.ORDER).toList();
        enums = enums.stream().sorted(EnumModel.ORDER).toList();
        Set<String> qualifiedNames = new HashSet<>();
        for (EnumModel enumModel : enums) {
            if (!qualifiedNames.add(enumModel.qualifiedName())) {
                throw new IllegalArgumentException(
                        "duplicate enum model " + enumModel.qualifiedName());
            }
        }
    }

    Optional<EnumModel> enumModel(String javaType) {
        return enums.stream()
                .filter(enumModel -> enumModel.qualifiedName().equals(javaType))
                .findFirst();
    }

    String transportType(String javaType) {
        return enumModel(javaType).isPresent() ? "long" : javaType;
    }

    record ParameterModel(String name, String type) {}

    record MethodModel(
            String javaName,
            String exportedName,
            String returnType,
            List<ParameterModel> parameters) {
        static final Comparator<MethodModel> ORDER =
                Comparator.comparing(MethodModel::exportedName)
                        .thenComparing(MethodModel::javaName);

        MethodModel {
            parameters = List.copyOf(parameters);
        }
    }

    record ConstantModel(
            String fieldName,
            String exportedName,
            String type,
            String enumName,
            long value,
            boolean bitfield) {
        static final Comparator<ConstantModel> ORDER =
                Comparator.comparing(ConstantModel::exportedName)
                        .thenComparing(ConstantModel::fieldName)
                        .thenComparing(ConstantModel::type)
                        .thenComparing(ConstantModel::enumName)
                        .thenComparingLong(ConstantModel::value)
                        .thenComparing(ConstantModel::bitfield);
    }

    record PropertyModel(
            String fieldName,
            String exportedName,
            String type,
            String getter,
            String setter,
            int index,
            String groupName,
            String groupPrefix,
            String subgroupName,
            String subgroupPrefix) {
        static final Comparator<PropertyModel> ORDER =
                Comparator.comparing(PropertyModel::exportedName)
                        .thenComparing(PropertyModel::fieldName)
                        .thenComparing(PropertyModel::type)
                        .thenComparing(PropertyModel::getter)
                        .thenComparing(PropertyModel::setter)
                        .thenComparingInt(PropertyModel::index)
                        .thenComparing(PropertyModel::groupName)
                        .thenComparing(PropertyModel::groupPrefix)
                        .thenComparing(PropertyModel::subgroupName)
                        .thenComparing(PropertyModel::subgroupPrefix);
    }

    record SignalModel(String javaName, String exportedName, List<ParameterModel> parameters) {
        static final Comparator<SignalModel> ORDER =
                Comparator.comparing(SignalModel::exportedName)
                        .thenComparing(SignalModel::javaName);

        SignalModel {
            parameters = List.copyOf(parameters);
        }
    }

    enum EnumOrigin {
        GENERATED,
        USER
    }

    record EnumConstantModel(String javaName, long value) {
        static final Comparator<EnumConstantModel> ORDER =
                Comparator.comparing(EnumConstantModel::javaName)
                        .thenComparingLong(EnumConstantModel::value);
    }

    record EnumModel(String qualifiedName, EnumOrigin origin, List<EnumConstantModel> constants) {
        static final Comparator<EnumModel> ORDER = Comparator.comparing(EnumModel::qualifiedName);

        EnumModel {
            constants = constants.stream().sorted(EnumConstantModel.ORDER).toList();
        }
    }
}
