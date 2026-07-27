# The native bridge resolves this exact class name and its versioned JNI methods.
-keepnames class games.cafecito.foundry.java.FoundryJavaInitializer
-keepclassmembers,includedescriptorclasses class games.cafecito.foundry.java.FoundryJavaInitializer {
    private static native boolean nativeBootstrapV1(java.lang.ClassLoader, games.cafecito.foundry.runtime.FoundryBridgeCallbacks, java.lang.String, java.lang.String, java.lang.String, java.lang.String);
    private static native long nativeCreateContextV1();
    private static native long nativeInvokeCallbackV1(long, long, long[]);
    private static native long nativeInvokeCallbackOnThreadV1(long, long, long[]);
    private static native boolean nativeShutdownContextV1(long);
    private static native void nativeShutdownBridgeV1();
}

# The production engine exposes only the frozen, versioned JNI boundary.
-keepnames class games.cafecito.foundry.java.FoundryNativeEngine
-keepclassmembers,includedescriptorclasses class games.cafecito.foundry.java.FoundryNativeEngine {
    private static games.cafecito.foundry.java.FoundryNativeEngine$NativeVariantSnapshot nativeSnapshotV1(long, games.cafecito.foundry.types.Variant);
    private static games.cafecito.foundry.types.Variant nativeVariantFromSnapshotV1(long, long, games.cafecito.foundry.java.FoundryNativeEngine$NativeVariantSnapshot);
    private static games.cafecito.foundry.types.Variant invokeLocalCallableV1(long, games.cafecito.foundry.runtime.FoundryCallable, games.cafecito.foundry.types.Variant[]);
    private static java.lang.String[] nativeDispatchArgumentTypesV1(games.cafecito.foundry.runtime.FoundryNativeDispatch);
    private static native games.cafecito.foundry.runtime.FoundryEngine$CallResult nativeCallV1(long, long, games.cafecito.foundry.runtime.FoundryNativeDispatch, games.cafecito.foundry.types.Variant[]);
    private static native games.cafecito.foundry.types.Variant nativeDecodeVariantV1(long, long);
    private static native long nativeEncodeVariantV1(long, games.cafecito.foundry.types.Variant);
    private static native boolean nativeIsObjectValidV1(long, long);
    private static native java.lang.String nativeObjectTypeV1(long, long);
    private static native long nativeInstantiateV1(long, java.lang.String);
    private static native void nativeRetainV1(long, long);
    private static native void nativeReleaseV1(long, long);
    private static native long nativeSingletonV1(long, java.lang.String);
    private static native void nativeReportCallbackExceptionV1(long, long, java.lang.Throwable);
    private static native void nativeRegisterExtensionClassV1(long, games.cafecito.foundry.runtime.FoundryClassDescriptor);
    private static native void nativeUnregisterExtensionClassV1(long, java.lang.String);
}

-keep,allowoptimization class games.cafecito.foundry.java.FoundryNativeEngine$NativeVariantSnapshot {
    <init>(int, long[], double[], java.lang.String, games.cafecito.foundry.types.Variant[], games.cafecito.foundry.types.Variant[], long, long, games.cafecito.foundry.runtime.FoundryCallable, int);
    int type();
    long[] integers();
    double[] reals();
    java.lang.String text();
    games.cafecito.foundry.types.Variant[] keys();
    games.cafecito.foundry.types.Variant[] values();
    long nativeContext();
    long nativeHandle();
    games.cafecito.foundry.runtime.FoundryCallable callback();
    int callableArity();
}

-keep,allowoptimization class games.cafecito.foundry.runtime.FoundryNativeDispatch {
    java.lang.String identity();
    games.cafecito.foundry.runtime.FoundryNativeDispatch$Kind kind();
    java.lang.String ownerNativeType();
    java.lang.String nativeName();
    long compatibilityHash();
    int constructorIndex();
    int minimumArgumentCount();
    java.lang.String returnNativeType();
    java.lang.String getterIdentity();
    java.lang.String getterNativeName();
    long getterCompatibilityHash();
    java.lang.String setterIdentity();
    java.lang.String setterNativeName();
    long setterCompatibilityHash();
    boolean vararg();
    boolean staticCall();
}

-keep,allowoptimization class games.cafecito.foundry.runtime.FoundryNativeDispatch$Kind {
    int wireCode();
}

-keep,allowoptimization class games.cafecito.foundry.runtime.FoundryEngine$CallResult {
    <init>(games.cafecito.foundry.types.Variant, games.cafecito.foundry.runtime.FoundryCallError, int, java.lang.String);
}

-keep,allowoptimization enum games.cafecito.foundry.runtime.FoundryCallError {
    public static games.cafecito.foundry.runtime.FoundryCallError valueOf(java.lang.String);
}

# The generated application bootstrap is the single registry handoff.
-keep,allowoptimization class games.cafecito.foundry.generated.FoundryGeneratedBootstrap {
    public static games.cafecito.foundry.runtime.FoundryRegistryBootstrap bootstrap();
}

# Android instantiates the generated provider, whose typed hook is implemented on the stable base.
-keep,allowoptimization class games.cafecito.foundry.generated.FoundryGeneratedStartupProvider
-keep,allowoptimization class games.cafecito.foundry.java.FoundryJavaStartupProvider {
    protected games.cafecito.foundry.runtime.FoundryRegistryBootstrap bootstrap();
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
