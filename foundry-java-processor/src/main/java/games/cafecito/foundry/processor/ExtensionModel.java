package games.cafecito.foundry.processor;

import java.util.Comparator;
import java.util.List;

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
        List<MethodModel> overrides) {
    ExtensionModel {
        initializationDependencies = initializationDependencies.stream().sorted().toList();
        methods = methods.stream().sorted(MethodModel.ORDER).toList();
        constants = constants.stream().sorted(ConstantModel.ORDER).toList();
        properties = properties.stream().sorted(PropertyModel.ORDER).toList();
        signals = signals.stream().sorted(SignalModel.ORDER).toList();
        overrides = overrides.stream().sorted(MethodModel.ORDER).toList();
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
}
