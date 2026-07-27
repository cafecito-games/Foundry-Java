package games.cafecito.foundry.gradle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Verifies the final merged startup provider before the manifest reaches packaging. */
public abstract class VerifyStartupManifestTask extends DefaultTask {
    private static final String ANDROID_NAMESPACE =
            "http://schemas.android.com/apk/res/android";
    private static final String REGISTRY_INDEX = "foundry_java/registry-index-v2.txt";

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputManifest();

    @OutputFile
    public abstract RegularFileProperty getOutputManifest();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getRegistryAssetsDirectory();

    @Input
    public abstract Property<String> getVariantName();

    @Input
    public abstract Property<String> getExpectedProviderClass();

    @Input
    public abstract Property<String> getExpectedAuthority();

    @TaskAction
    public void verify() throws Exception {
        Path input = getInputManifest().get().getAsFile().toPath();
        Path output = getOutputManifest().get().getAsFile().toPath();
        Path registryIndex =
                getRegistryAssetsDirectory().get().getAsFile().toPath().resolve(REGISTRY_INDEX);
        if (Files.isRegularFile(registryIndex)) {
            verifyModuleBearingManifest(input);
        }
        Files.createDirectories(output.getParent());
        Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
    }

    private void verifyModuleBearingManifest(Path manifest) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        NodeList providers =
                factory.newDocumentBuilder().parse(manifest.toFile()).getElementsByTagName("provider");
        String expectedProvider = getExpectedProviderClass().get();
        String expectedAuthority = getExpectedAuthority().get();
        List<Element> expectedProviders = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        for (int index = 0; index < providers.getLength(); index++) {
            Element provider = (Element) providers.item(index);
            String providerClass = provider.getAttributeNS(ANDROID_NAMESPACE, "name");
            String authorities = provider.getAttributeNS(ANDROID_NAMESPACE, "authorities");
            if (providerClass.equals(expectedProvider)) {
                expectedProviders.add(provider);
            } else if (authorityContains(authorities, expectedAuthority)) {
                conflicts.add(providerClass);
            }
        }
        Collections.sort(conflicts);
        if (!conflicts.isEmpty()) {
            throw new GradleException(
                    "Foundry-Java variant "
                            + getVariantName().get()
                            + " startup authority "
                            + expectedAuthority
                            + " is already declared by provider(s) "
                            + conflicts
                            + "; expected only "
                            + expectedProvider
                            + ".");
        }
        if (expectedProviders.size() != 1) {
            throw new GradleException(
                    "Foundry-Java variant "
                            + getVariantName().get()
                            + " requires exactly one provider "
                            + expectedProvider
                            + " with startup authority "
                            + expectedAuthority
                            + "; found "
                            + expectedProviders.size()
                            + ".");
        }
        verifyExpectedProvider(expectedProviders.get(0), expectedProvider, expectedAuthority);
    }

    private void verifyExpectedProvider(
            Element provider, String expectedProvider, String expectedAuthority) {
        List<String> violations = new ArrayList<>();
        String authority = provider.getAttributeNS(ANDROID_NAMESPACE, "authorities").trim();
        if (!authority.equals(expectedAuthority)) {
            violations.add(
                    "authority expected=" + expectedAuthority + ", actual=" + display(authority));
        }
        String exported = provider.getAttributeNS(ANDROID_NAMESPACE, "exported").trim();
        if (!exported.equals("false")) {
            violations.add("exported expected=false, actual=" + display(exported));
        }
        String process = provider.getAttributeNS(ANDROID_NAMESPACE, "process").trim();
        if (!process.isEmpty()) {
            violations.add("process expected=<empty>, actual=" + process);
        }
        String initOrder = provider.getAttributeNS(ANDROID_NAMESPACE, "initOrder").trim();
        if (!initOrder.equals("100")) {
            violations.add("initOrder expected=100, actual=" + display(initOrder));
        }
        String enabled = provider.getAttributeNS(ANDROID_NAMESPACE, "enabled").trim();
        if (enabled.equalsIgnoreCase("false")) {
            violations.add("enabled expected=not false, actual=" + enabled);
        }
        if (!violations.isEmpty()) {
            throw new GradleException(
                    "Foundry-Java variant "
                            + getVariantName().get()
                            + " provider "
                            + expectedProvider
                            + " violates the final startup contract: "
                            + String.join("; ", violations)
                            + ".");
        }
    }

    private static String display(String value) {
        return value.isEmpty() ? "<empty>" : value;
    }

    private static boolean authorityContains(String authorities, String expected) {
        for (String authority : authorities.split(";")) {
            if (authority.trim().equals(expected)) {
                return true;
            }
        }
        return false;
    }
}
