package games.cafecito.foundry.processor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import games.cafecito.foundry.api.model.ApiInputs;
import games.cafecito.foundry.api.model.CompatibilityManifest;
import games.cafecito.foundry.api.model.FoundryApi;
import games.cafecito.foundry.api.model.FoundryApiParser;
import games.cafecito.foundry.generator.FoundrySourceGenerator;
import games.cafecito.foundry.generator.GeneratedTree;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeneratedBindingIntegrationTest {
    @Test
    void compilesAnExtensionAgainstTheActualGeneratedNodeContract() throws IOException {
        Map<String, String> sources = generatedSources();
        sources.put(
                "demo.SpinningNode",
                """
                package demo;
                import games.cafecito.foundry.annotations.*;
                import games.cafecito.foundry.generated.classes.Node;
                import games.cafecito.foundry.runtime.FoundryBindingContext;
                import games.cafecito.foundry.runtime.ObjectLease;
                @FoundryClass(base = Node.class)
                public final class SpinningNode extends Node {
                    private Node target;
                    public SpinningNode(FoundryBindingContext context, ObjectLease lease) {
                        super(context, lease);
                    }
                    @FoundryMethod
                    public Node echo(Node value) { return value; }
                    @FoundryProperty(getter = "target", setter = "target")
                    private Node targetProperty;
                    public Node target() { return targetProperty; }
                    public void target(Node value) { targetProperty = value; }
                    @FoundrySignal
                    public interface Changed { void emitted(Node value); }
                    @FoundryOverride
                    public void onProcess(double delta) { target = null; }
                }
                """);

        ProcessorCompilation.Result result =
                ProcessorCompilation.compileWithOptions(
                        sources, "generated-binding-module", java.util.List.of("-Werror"));

        assertTrue(result.successful(), () -> result.errorMessages().toString());
        String trampoline =
                result.generatedSources().get("demo/SpinningNode_FoundryTrampoline.java");
        assertTrue(
                trampoline.contains(
                        "FoundryBindingContext context,\n"
                                + "            games.cafecito.foundry.runtime.ObjectLease lease)"),
                trampoline);
        assertTrue(trampoline.contains("receiver.onProcess((double) arguments[0])"), trampoline);
        String descriptor =
                new String(
                        result.classOutput()
                                .get(
                                        "META-INF/foundry-java/modules/"
                                                + "generated-binding-module.descriptor"),
                        StandardCharsets.UTF_8);
        assertTrue(
                descriptor.contains("override=demo.SpinningNode|_process|onProcess|void(double)"),
                descriptor);
    }

    private static Map<String, String> generatedSources() throws IOException {
        Path acceptedDirectory =
                Path.of(System.getProperty("user.dir")).resolve("../api/current").normalize();
        ApiInputs inputs = ApiInputs.load(acceptedDirectory);
        FoundryApi api = FoundryApiParser.parse(inputs);
        FoundrySourceGenerator.Metadata metadata =
                new FoundrySourceGenerator.Metadata(
                        inputs.extensionApiSha256(),
                        inputs.interfaceHeaderSha256(),
                        inputs.provenance().foundryCommit(),
                        inputs.provenance().foundryVersion(),
                        inputs.provenance().generatorVersion(),
                        inputs.provenance().bridgeContractVersion());
        GeneratedTree generated =
                new FoundrySourceGenerator()
                        .generate(api, metadata, CompatibilityManifest.parse(api, inputs));
        Map<String, String> sources = new LinkedHashMap<>();
        generated
                .sources()
                .forEach(
                        (path, source) ->
                                sources.put(
                                        path.substring(0, path.length() - ".java".length())
                                                .replace('/', '.'),
                                        source));
        return sources;
    }
}
