package games.cafecito.foundry.runtime;

/** Typed, reflection-free entry point emitted once for each Java extension module. */
@FunctionalInterface
public interface FoundryModuleProvider {
    FoundryModuleDescriptor descriptor();
}
