package games.cafecito.foundry.api.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Immutable, normalized representation of one complete Foundry extension API dump. */
public final class FoundryApi {
    private final Header header;
    private final Map<String, List<Entity>> categories;
    private final List<Entity> entities;
    private final Map<String, Entity> entitiesByIdentity;
    private final JsonValue.JsonObject canonicalRoot;

    FoundryApi(
            Header header,
            Map<String, List<Entity>> categories,
            JsonValue.JsonObject canonicalRoot) {
        this.header = header;
        Map<String, List<Entity>> copiedCategories = new TreeMap<>();
        categories.forEach(
                (category, values) -> copiedCategories.put(category, List.copyOf(values)));
        this.categories = Collections.unmodifiableMap(new LinkedHashMap<>(copiedCategories));
        this.canonicalRoot = canonicalRoot;

        List<Entity> flattened = new ArrayList<>();
        Map<String, Entity> indexed = new LinkedHashMap<>();
        for (List<Entity> roots : this.categories.values()) {
            for (Entity root : roots) {
                flatten(root, flattened, indexed);
            }
        }
        entities = List.copyOf(flattened);
        entitiesByIdentity = Collections.unmodifiableMap(indexed);
    }

    public Header header() {
        return header;
    }

    public Map<String, List<Entity>> categories() {
        return categories;
    }

    public List<Entity> entities() {
        return entities;
    }

    public Optional<Entity> entity(String identity) {
        return Optional.ofNullable(entitiesByIdentity.get(identity));
    }

    public Map<String, Integer> categoryCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (var category : categories.entrySet()) {
            int count = 0;
            for (Entity entity : category.getValue()) {
                count += entity.descendantCount();
            }
            counts.put(category.getKey(), count);
        }
        return Collections.unmodifiableMap(counts);
    }

    public String canonicalJson() {
        return canonicalRoot.canonicalJson() + "\n";
    }

    private static void flatten(
            Entity entity, List<Entity> flattened, Map<String, Entity> indexed) {
        flattened.add(entity);
        if (indexed.put(entity.identity(), entity) != null) {
            throw new ApiInputException("Duplicate source identity " + entity.identity() + ".");
        }
        entity.children().forEach(child -> flatten(child, flattened, indexed));
    }

    public record Header(
            int versionMajor,
            int versionMinor,
            int versionPatch,
            String versionStatus,
            String versionBuild,
            String versionFullName,
            String precision) {
        public String apiVersion() {
            String normalizedStatus = versionStatus.replaceFirst("^([A-Za-z]+)([0-9]+)$", "$1.$2");
            String suffix = normalizedStatus.isBlank() ? "" : "-" + normalizedStatus;
            return versionMajor + "." + versionMinor + "." + versionPatch + suffix;
        }
    }

    /** One source-identified API object, retaining its normalized complete JSON payload. */
    public record Entity(
            String category,
            String identity,
            String sourcePath,
            String edge,
            int ordinal,
            JsonValue.JsonObject source,
            List<Entity> children) {
        public Entity {
            children = List.copyOf(children);
        }

        int descendantCount() {
            int count = 1;
            for (Entity child : children) {
                count += child.descendantCount();
            }
            return count;
        }

        Entity withPosition(String newEdge, int newOrdinal) {
            return new Entity(
                    category, identity, sourcePath, newEdge, newOrdinal, source, children);
        }
    }
}
