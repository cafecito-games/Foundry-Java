package games.cafecito.foundry.samples.java;

import games.cafecito.foundry.runtime.FoundryBindingContext;
import games.cafecito.foundry.runtime.FoundryBindingContextAware;
import games.cafecito.foundry.runtime.FoundryCallError;
import games.cafecito.foundry.runtime.FoundryClassDescriptor;
import games.cafecito.foundry.runtime.FoundryEngine;
import games.cafecito.foundry.types.Variant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * A scripted {@link FoundryEngine} that records every transport interaction.
 *
 * <p>The engine is authored entirely against the public {@code FoundryEngine} transport interface,
 * exactly as an application author would write a host double for their own tests. It exists so a
 * conformance assertion can name the method identity and argument list the binding layer produced
 * rather than merely observing that nothing crashed.
 */
public final class ScriptedEngine implements FoundryEngine, FoundryBindingContextAware {
    /** One observed transport call, retaining the evidence a conformance test asserts on. */
    public record CallRecord(long objectHandle, String methodIdentity, List<Variant> arguments) {
        public CallRecord {
            Objects.requireNonNull(methodIdentity, "methodIdentity");
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        }
    }

    private final List<CallRecord> calls = Collections.synchronizedList(new ArrayList<>());
    private final List<String> registrations = Collections.synchronizedList(new ArrayList<>());
    private final List<String> unregistrations = Collections.synchronizedList(new ArrayList<>());
    private final List<Throwable> reportedExceptions =
            Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Function<List<Variant>, CallResult>> responsesByPrefix =
            new ConcurrentHashMap<>();
    private final Map<Long, String> objectTypes = new ConcurrentHashMap<>();
    private final Map<Long, Long> retainCounts = new ConcurrentHashMap<>();
    private final Map<Long, Long> releaseCounts = new ConcurrentHashMap<>();
    private final Map<Long, Variant> variantsByHandle = new ConcurrentHashMap<>();
    private final Map<String, Long> singletons = new ConcurrentHashMap<>();
    private final Map<String, Long> instantiations = new ConcurrentHashMap<>();
    private final AtomicLong nextHandle = new AtomicLong(1000);
    private final AtomicLong nextVariantHandle = new AtomicLong(1);
    private volatile Runnable beforeCall = () -> {};
    private volatile FoundryBindingContext bindingContext;

    /**
     * Receives the binding generation this engine transports for.
     *
     * <p>This engine stands in for the FoundryExtension/JNI bridge rather than for an application,
     * so it implements the bridge-side attachment SPI. Application code never needs to.
     */
    @Override
    public void attachBindingContext(FoundryBindingContext context) {
        bindingContext = Objects.requireNonNull(context, "context");
    }

    /** Returns the attached binding generation, or fails when none has been published yet. */
    public FoundryBindingContext bindingContext() {
        FoundryBindingContext attached = bindingContext;
        if (attached == null) {
            throw new IllegalStateException("No Foundry binding context has been attached yet.");
        }
        return attached;
    }

    /** Declares a live engine object of the given Foundry type and returns its opaque handle. */
    public long declareObject(String foundryType) {
        long handle = nextHandle.incrementAndGet();
        objectTypes.put(handle, Objects.requireNonNull(foundryType, "foundryType"));
        return handle;
    }

    /** Makes a previously declared handle invalid, as engine-side destruction would. */
    public void destroyObject(long objectHandle) {
        objectTypes.remove(objectHandle);
    }

    /** Declares the handle that {@link #singleton} returns for a singleton name. */
    public long declareSingleton(String name, String foundryType) {
        long handle = declareObject(foundryType);
        singletons.put(Objects.requireNonNull(name, "name"), handle);
        return handle;
    }

    /** Declares the handle that {@link #instantiate} returns for an engine class name. */
    public long declareInstantiable(String className, String foundryType) {
        long handle = declareObject(foundryType);
        instantiations.put(Objects.requireNonNull(className, "className"), handle);
        return handle;
    }

    /**
     * Scripts a successful result for every method identity starting with {@code methodPrefix}.
     *
     * <p>A Foundry method identity carries an API-derived numeric suffix, for example {@code
     * classes/Node/methods/get_name#123456}. Samples therefore script and assert on the stable
     * identity prefix so an accepted engine-API bump does not silently rewrite the matrix.
     */
    public void respondWith(String methodPrefix, Variant value) {
        Objects.requireNonNull(value, "value");
        responsesByPrefix.put(
                requirePrefix(methodPrefix), arguments -> CallResult.success(value));
    }

    /** Scripts a successful result computed from the observed arguments. */
    public void respondWith(String methodPrefix, Function<List<Variant>, Variant> value) {
        Objects.requireNonNull(value, "value");
        responsesByPrefix.put(
                requirePrefix(methodPrefix),
                arguments -> CallResult.success(value.apply(arguments)));
    }

    /** Scripts an engine-reported call error for one Foundry method identity prefix. */
    public void respondWithError(
            String methodPrefix, FoundryCallError error, int argumentIndex, String expectedType) {
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(expectedType, "expectedType");
        responsesByPrefix.put(
                requirePrefix(methodPrefix),
                arguments -> new CallResult(Variant.nil(), error, argumentIndex, expectedType));
    }

    /** Runs the given action on the calling thread immediately before every transport call. */
    public void beforeCall(Runnable action) {
        beforeCall = Objects.requireNonNull(action, "action");
    }

    public List<CallRecord> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    /** Returns every observed call whose identity starts with the prefix, in order. */
    public List<CallRecord> callsTo(String methodPrefix) {
        String prefix = requirePrefix(methodPrefix);
        synchronized (calls) {
            return calls.stream()
                    .filter(call -> matches(call.methodIdentity(), prefix))
                    .toList();
        }
    }

    /** Returns the single observed call whose identity starts with the prefix. */
    public CallRecord onlyCallTo(String methodPrefix) {
        List<CallRecord> observed = callsTo(methodPrefix);
        if (observed.size() != 1) {
            throw new AssertionError(
                    "Expected exactly one call to "
                            + methodPrefix
                            + " but observed "
                            + observed.size()
                            + " in "
                            + methodIdentities());
        }
        return observed.get(0);
    }

    private static String requirePrefix(String methodPrefix) {
        String checked = Objects.requireNonNull(methodPrefix, "methodPrefix");
        if (checked.isBlank()) {
            throw new IllegalArgumentException("A Foundry method identity prefix must be set.");
        }
        return checked;
    }

    private static boolean matches(String methodIdentity, String prefix) {
        return methodIdentity.equals(prefix) || methodIdentity.startsWith(prefix + "#");
    }

    public List<String> methodIdentities() {
        synchronized (calls) {
            return calls.stream().map(CallRecord::methodIdentity).toList();
        }
    }

    public List<String> registrations() {
        synchronized (registrations) {
            return List.copyOf(registrations);
        }
    }

    public List<String> unregistrations() {
        synchronized (unregistrations) {
            return List.copyOf(unregistrations);
        }
    }

    public List<Throwable> reportedExceptions() {
        synchronized (reportedExceptions) {
            return List.copyOf(reportedExceptions);
        }
    }

    public long retainCount(long objectHandle) {
        return retainCounts.getOrDefault(objectHandle, 0L);
    }

    public long releaseCount(long objectHandle) {
        return releaseCounts.getOrDefault(objectHandle, 0L);
    }

    @Override
    public void registerExtensionClass(long contextHandle, FoundryClassDescriptor descriptor) {
        registrations.add(Objects.requireNonNull(descriptor, "descriptor").foundryName());
    }

    @Override
    public void unregisterExtensionClass(long contextHandle, String foundryName) {
        unregistrations.add(Objects.requireNonNull(foundryName, "foundryName"));
    }

    @Override
    public CallResult call(
            long contextHandle, long objectHandle, String methodIdentity, List<Variant> arguments) {
        beforeCall.run();
        calls.add(new CallRecord(objectHandle, methodIdentity, arguments));
        Function<List<Variant>, CallResult> response = null;
        String longestPrefix = "";
        for (Map.Entry<String, Function<List<Variant>, CallResult>> candidate :
                responsesByPrefix.entrySet()) {
            if (matches(methodIdentity, candidate.getKey())
                    && candidate.getKey().length() > longestPrefix.length()) {
                longestPrefix = candidate.getKey();
                response = candidate.getValue();
            }
        }
        if (response == null) {
            throw new IllegalStateException(
                    "The conformance sample made an unscripted engine call: " + methodIdentity);
        }
        return response.apply(List.copyOf(arguments));
    }

    @Override
    public Variant decodeVariant(long contextHandle, long variantHandle) {
        return Optional.ofNullable(variantsByHandle.get(variantHandle)).orElseGet(Variant::nil);
    }

    @Override
    public long encodeVariant(long contextHandle, Variant value) {
        long handle = nextVariantHandle.incrementAndGet();
        variantsByHandle.put(handle, Objects.requireNonNull(value, "value"));
        return handle;
    }

    @Override
    public boolean isObjectValid(long contextHandle, long objectHandle) {
        return objectTypes.containsKey(objectHandle);
    }

    @Override
    public String objectType(long contextHandle, long objectHandle) {
        return objectTypes.getOrDefault(objectHandle, "");
    }

    @Override
    public long instantiate(long contextHandle, String className) {
        Long handle = instantiations.get(Objects.requireNonNull(className, "className"));
        if (handle == null) {
            throw new IllegalStateException(
                    "The conformance sample instantiated an undeclared engine class: " + className);
        }
        return handle;
    }

    @Override
    public void retain(long contextHandle, long objectHandle) {
        retainCounts.merge(objectHandle, 1L, Long::sum);
    }

    @Override
    public void release(long contextHandle, long objectHandle) {
        releaseCounts.merge(objectHandle, 1L, Long::sum);
    }

    @Override
    public long singleton(long contextHandle, String name) {
        Long handle = singletons.get(Objects.requireNonNull(name, "name"));
        if (handle == null) {
            throw new IllegalStateException(
                    "The conformance sample requested an undeclared singleton: " + name);
        }
        return handle;
    }

    @Override
    public void reportCallbackException(
            long contextHandle, long callbackHandle, Throwable failure) {
        reportedExceptions.add(Objects.requireNonNull(failure, "failure"));
    }

    /** Returns the recorded transport interactions as ordered, human-readable evidence. */
    public Map<String, List<String>> evidence() {
        Map<String, List<String>> summary = new LinkedHashMap<>();
        summary.put("calls", methodIdentities());
        summary.put("registrations", registrations());
        summary.put("unregistrations", unregistrations());
        summary.put(
                "reportedExceptions",
                reportedExceptions().stream().map(failure -> failure.getClass().getName()).toList());
        return summary;
    }
}
