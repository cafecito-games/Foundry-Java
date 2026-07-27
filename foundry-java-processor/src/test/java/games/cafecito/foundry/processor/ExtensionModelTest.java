package games.cafecito.foundry.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExtensionModelTest {
    @Test
    void freezesAUniqueQualifiedEnumInventoryAndExactTransportLookup() {
        var user =
                new ExtensionModel.EnumModel(
                        "demo.UserState",
                        ExtensionModel.EnumOrigin.USER,
                        List.of(
                                new ExtensionModel.EnumConstantModel("ZETA", Long.MAX_VALUE),
                                new ExtensionModel.EnumConstantModel("ALPHA", Long.MIN_VALUE)));
        var generated =
                new ExtensionModel.EnumModel(
                        "games.cafecito.foundry.generated.enums.Error",
                        ExtensionModel.EnumOrigin.GENERATED,
                        List.of());
        ExtensionModel model = model(List.of(user, generated));

        assertEquals(
                List.of("demo.UserState", "games.cafecito.foundry.generated.enums.Error"),
                model.enums().stream().map(ExtensionModel.EnumModel::qualifiedName).toList());
        assertEquals(
                List.of("ALPHA", "ZETA"),
                model.enumModel("demo.UserState").orElseThrow().constants().stream()
                        .map(ExtensionModel.EnumConstantModel::javaName)
                        .toList());
        assertEquals(
                ExtensionModel.EnumOrigin.GENERATED,
                model.enumModel("games.cafecito.foundry.generated.enums.Error")
                        .orElseThrow()
                        .origin());
        assertEquals("long", model.transportType("demo.UserState"));
        assertEquals("long", model.transportType("games.cafecito.foundry.generated.enums.Error"));
        assertEquals("java.lang.String", model.transportType("java.lang.String"));
        assertEquals(java.util.Optional.empty(), model.enumModel("State"));
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        model.enums()
                                .add(
                                        new ExtensionModel.EnumModel(
                                                "demo.Other",
                                                ExtensionModel.EnumOrigin.USER,
                                                List.of(
                                                        new ExtensionModel.EnumConstantModel(
                                                                "ONLY", 1)))));
    }

    @Test
    void rejectsDuplicateQualifiedEnumModels() {
        var first =
                new ExtensionModel.EnumModel(
                        "demo.State",
                        ExtensionModel.EnumOrigin.USER,
                        List.of(new ExtensionModel.EnumConstantModel("FIRST", 1)));
        var second =
                new ExtensionModel.EnumModel(
                        "demo.State",
                        ExtensionModel.EnumOrigin.USER,
                        List.of(new ExtensionModel.EnumConstantModel("SECOND", 2)));

        assertThrows(IllegalArgumentException.class, () -> model(List.of(first, second)));
    }

    private static ExtensionModel model(List<ExtensionModel.EnumModel> enums) {
        return new ExtensionModel(
                "demo.Extension",
                "demo",
                "Extension",
                "Extension",
                "demo.EngineNode",
                "SCENE",
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                enums);
    }
}
