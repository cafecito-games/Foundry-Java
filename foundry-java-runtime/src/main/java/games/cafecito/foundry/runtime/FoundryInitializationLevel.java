package games.cafecito.foundry.runtime;

import java.util.Optional;

/** FoundryExtension initialization levels in their native ABI order. */
public enum FoundryInitializationLevel {
    CORE(0),
    SERVERS(1),
    SCENE(2),
    EDITOR(3);

    private final int code;

    FoundryInitializationLevel(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    static Optional<FoundryInitializationLevel> fromCode(int code) {
        for (FoundryInitializationLevel level : values()) {
            if (level.code == code) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }
}
