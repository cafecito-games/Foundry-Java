package games.cafecito.foundry.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import games.cafecito.foundry.api.model.ApiInputException;
import games.cafecito.foundry.api.model.CompatibilityManifest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Freezes the realization map grammar, ordering, and two-state invariant. */
class RealizationMapTest {
    private static final String REASON = "WS5_MODEL_AND_GENERATOR_REPRESENTABLE";
    private static final String OWNER = "games.cafecito.foundry.generated.classes.ExampleNode";

    @Test
    void entriesRenderOneDeterministicLinePerSourceIdentity() {
        RealizationMap map =
                RealizationMap.of(
                        List.of(
                                RealizationMap.Entry.notRealized(
                                        "classes/ExampleNode/methods/get_value/arguments/index",
                                        CompatibilityManifest.Status.SUPPORTED,
                                        REASON,
                                        NonRealizationReason.ARGUMENT_REALIZED_IN_MEMBER_SIGNATURE
                                                .name()),
                                RealizationMap.Entry.realized(
                                        "classes/ExampleNode",
                                        CompatibilityManifest.Status.SUPPORTED,
                                        REASON,
                                        List.of(
                                                JavaMember.ofMethod(
                                                        OWNER, "setValue", List.of("long"), "void"),
                                                JavaMember.ofType(OWNER)))));

        assertEquals(
                RealizationMap.FORMAT
                        + "\n"
                        + "classes/ExampleNode\tsupported\t"
                        + REASON
                        + "\trealized\t"
                        + OWNER
                        + "#<type>:"
                        + OWNER
                        + ";"
                        + OWNER
                        + "#setValue:(long)void\n"
                        + "classes/ExampleNode/methods/get_value/arguments/index\tsupported\t"
                        + REASON
                        + "\tnot-realized\tARGUMENT_REALIZED_IN_MEMBER_SIGNATURE\n",
                map.render());
        assertEquals(map.render(), RealizationMap.parse(map.render()).render());
        assertEquals(map.sha256(), RealizationMap.parse(map.render()).sha256());
    }

    @Test
    void theDigestMatchesTheDigestOfTheWholeRendering() throws Exception {
        RealizationMap map =
                RealizationMap.of(
                        List.of(
                                RealizationMap.Entry.realized(
                                        "classes/ExampleNode",
                                        CompatibilityManifest.Status.SUPPORTED,
                                        REASON,
                                        List.of(
                                                JavaMember.ofMethod(
                                                        OWNER, "setValue", List.of("long"), "void"),
                                                JavaMember.ofType(OWNER))),
                                RealizationMap.Entry.notRealized(
                                        "classes/ExampleNode/methods/get_value/arguments/index",
                                        CompatibilityManifest.Status.SUPPORTED,
                                        REASON,
                                        NonRealizationReason.ARGUMENT_REALIZED_IN_MEMBER_SIGNATURE
                                                .name())));

        assertEquals(
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(map.render().getBytes(StandardCharsets.UTF_8))),
                map.sha256());
    }

    @Test
    void anEntryCannotResolveToBothStatesOrToNeither() {
        assertThrows(
                ApiInputException.class,
                () ->
                        new RealizationMap.Entry(
                                "classes/ExampleNode",
                                CompatibilityManifest.Status.SUPPORTED,
                                REASON,
                                List.of(JavaMember.ofType(OWNER)),
                                NonRealizationReason.ARGUMENT_REALIZED_IN_MEMBER_SIGNATURE.name()));
        assertThrows(
                ApiInputException.class,
                () ->
                        new RealizationMap.Entry(
                                "classes/ExampleNode",
                                CompatibilityManifest.Status.SUPPORTED,
                                REASON,
                                List.of(),
                                ""));
    }

    @Test
    void aSourceIdentityCannotBeCoveredTwice() {
        RealizationMap.Entry entry =
                RealizationMap.Entry.realized(
                        "classes/ExampleNode",
                        CompatibilityManifest.Status.SUPPORTED,
                        REASON,
                        List.of(JavaMember.ofType(OWNER)));

        assertThrows(ApiInputException.class, () -> RealizationMap.of(List.of(entry, entry)));
    }

    @Test
    void realizedMembersCarryOwnerNameAndErasedSignature() {
        JavaMember method =
                JavaMember.ofMethod(
                        OWNER,
                        "getChildren",
                        List.of("games.cafecito.foundry.runtime.FoundryBindingContext", "long"),
                        "games.cafecito.foundry.types.FoundryArray");

        assertEquals(OWNER, method.owner());
        assertEquals("getChildren", method.name());
        assertEquals(
                "(games.cafecito.foundry.runtime.FoundryBindingContext,long)"
                        + "games.cafecito.foundry.types.FoundryArray",
                method.erasedSignature());
        assertEquals(method, JavaMember.parse(method.render()));
        assertEquals(
                "games.cafecito.foundry.generated.classes.ExampleNode",
                JavaMember.ofField(OWNER + ".ProcessMode", "INHERIT", OWNER + ".ProcessMode")
                        .topLevelOwner());
    }
}
