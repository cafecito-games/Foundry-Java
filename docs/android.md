# Android integration

Android integration belongs exclusively in `foundry-java-android`. The module is an Android library,
while API-model, annotations, and runtime remain Android-free. Do not bundle, link, load, or
redistribute `libfoundry_android.so`.

`FoundryJavaInitializer` loads `libfoundry_java.so` and supplies its defining application class
loader to the versioned native bootstrap. Native code retains that loader and the runtime callback
adapter as JNI global references only while the bridge is live. Calls originating on a native
thread attach it to the JVM for the duration of the call and detach only when the bridge performed
the attachment.

Java exceptions never cross JNI. The bridge clears them, reports them through Foundry's public error
interface when available, and returns the documented safe default. The Android instrumentation
fixture exercises bootstrap, argument order, reentrant callbacks, native-thread attachment,
exception containment, shutdown, and rejection of stale contexts.

The release AAR contains one `libfoundry_java.so` for each supported ABI. Its ELF dependencies and
exports are checked during CI. It neither packages nor links `libfoundry_android.so` or `libjvm.so`;
the application supplies the Foundry host independently.
