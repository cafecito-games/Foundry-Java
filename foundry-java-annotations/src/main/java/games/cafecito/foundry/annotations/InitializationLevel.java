package games.cafecito.foundry.annotations;

/** Foundry initialization levels in engine startup order. */
public enum InitializationLevel {
    /** Core value and object infrastructure. */
    CORE,
    /** Engine server singletons. */
    SERVERS,
    /** Scene-tree and gameplay classes. */
    SCENE,
    /** Editor-only classes. */
    EDITOR
}
