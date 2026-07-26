# The native bridge resolves this exact class name and its versioned JNI methods.
-keepnames class games.cafecito.foundry.java.FoundryJavaInitializer
-keepclassmembers,includedescriptorclasses class games.cafecito.foundry.java.FoundryJavaInitializer {
    native <methods>;
}

# The generated application bootstrap is the single registry handoff.
-keep,allowoptimization class games.cafecito.foundry.generated.FoundryGeneratedBootstrap {
    public static games.cafecito.foundry.runtime.FoundryRegistryBootstrap bootstrap();
}

# Processor-emitted providers expose one direct, typed descriptor entry point.
-keepclasseswithmembers,allowoptimization,includedescriptorclasses class * implements games.cafecito.foundry.runtime.FoundryModuleProvider {
    public static games.cafecito.foundry.runtime.FoundryModuleProvider PROVIDER;
    public games.cafecito.foundry.runtime.FoundryModuleDescriptor descriptor();
}

# JNI resolves these callback names on the interface; implementations remain optimizable.
-keep,allowoptimization interface games.cafecito.foundry.runtime.FoundryBridgeCallbacks {
    boolean initialize(long, int);
    void deinitialize(long, int);
    long invoke(long, long, long[]);
    void invalidate(long);
}
