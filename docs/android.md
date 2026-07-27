# Android integration

Android integration belongs exclusively in `foundry-java-android`. The module is an Android library,
while API-model, annotations, and runtime remain Android-free. Do not bundle, link, load, or
redistribute `libfoundry_android.so`.

`FoundryJavaInitializer` loads `libfoundry_java.so` and supplies its defining application class
loader to the versioned native bootstrap. Native code retains that loader and the runtime callback
adapter as JNI global references only while the bridge is live. Calls originating on a native
thread attach it to the JVM for the duration of the call and detach only when the bridge performed
the attachment.

Production startup has two strict phases. The plugin-generated provider primes the typed bootstrap,
class loader, coordinator, and native library before `Application.onCreate()` without creating a
context. The public `foundry_java_library_init` entry and native CORE callback then resolve the
FoundryExtension table, create the production context and engine, and register generated classes.
Application and activity code must not call the direct initializer or create a context.

Java exceptions never cross JNI. The bridge clears them, reports them through Foundry's public error
interface when available, and returns the documented safe default. The Android instrumentation
fixture exercises the production provider/entry path, exact topological registration, callback
dispatch, native-thread attachment, exception containment, reverse teardown, invalidation, and
rejection of stale instances. It is a self-instrumenting APK: CI installs the single
`*-debug-androidTest.apk`, then drives and reads evidence from the
`games.cafecito.foundry.android.test` package. The release AAR namespace remains
`games.cafecito.foundry.android`.

Bridge shutdown is process-terminal. It releases the callback and class-loader global references;
applications that need a fresh bridge must start a new process rather than reinitialize static
state in the stopped process.

The release AAR contains one `libfoundry_java.so` for each supported ABI. Its ELF dependencies and
exports are checked during CI. It neither packages nor links `libfoundry_android.so` or `libjvm.so`;
the application supplies the Foundry host independently.
