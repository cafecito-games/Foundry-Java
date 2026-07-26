# Authoring Java extensions

Foundry-Java registers extension code at compile time. Apply the annotation processor to each
consumer module and give that module a stable lowercase identity:

```text
-Afoundry.module=my-gameplay
```

The identity becomes part of the generated registry class and descriptor path. Changing it is an
artifact compatibility change. It must be a stable lowercase hyphen-separated name whose first and
subsequent segments start with a letter, and whose generated Java package segment is not a Java 17
keyword.

## Extension declarations

An extension is a public, final, top-level class. Its direct Java superclass must match the
generated engine class named by `@FoundryClass`; ordinary application classes are not valid
extension bases. Extensions over generated engine bindings provide a public constructor accepting
the live `FoundryBindingContext` and its `ObjectLease`. The constructor may declare unchecked
exceptions, but not checked exceptions because generated construction cannot propagate them:

```java
@FoundryClass(base = Node3D.class)
@FoundryInitialization(InitializationLevel.SCENE)
public final class SpinningCube extends Node3D {
    public SpinningCube(FoundryBindingContext context, ObjectLease lease) {
        super(context, lease);
    }

    @FoundryProperty(getter = "speed", setter = "speed")
    private double speed;

    public double speed() {
        return speed;
    }

    public void speed(double value) {
        speed = value;
    }

    @FoundryMethod
    public void reset() {
        speed = 0.0;
    }

    @FoundryOverride
    public void onProcess(double delta) {
        rotateY(delta * speed);
    }

    @FoundrySignal
    public interface ResetDone {
        void emitted(double previousSpeed);
    }
}
```

`@FoundryClass`, `@FoundryMethod`, `@FoundryProperty`, `@FoundrySignal`, and
`@FoundryOverride` accept an optional exported `name`. Empty method, property, signal, and class
names use the Java declaration name. An empty override name instead uses the original Foundry
virtual identity preserved on the generated Java callback, such as `_process` for `onProcess`.
When an override name is explicit, it must equal that generated identity. All exported names in one
class share a namespace and must be unique.

An exported method or virtual override must be a public instance method without Java type
parameters or checked exceptions. Unchecked `RuntimeException` and `Error` declarations are
allowed. `@FoundryOverride` must exactly match a generator-owned virtual method on the declared
engine base by Java name and signature. The processor obtains the original Foundry name from the
CLASS-retained generated metadata; an ordinary same-named Java method is not a virtual callback. A
property annotation belongs on a field; its public getter returns exactly the field type and its
optional public setter accepts exactly that type. A signal annotation belongs on a nested interface
whose effective inherited method set contains exactly one abstract, `void` method. Generic inherited
SAM methods are resolved as members of the concrete signal interface, so
`Changed extends Parent<String>` exports `String`, not the parent's type variable.

Supported callback types are Java primitives, `void` returns, `String`, enums, generated API types
below `games.cafecito.foundry.api`, generated value types below
`games.cafecito.foundry.types`, generator-provenance engine/API types below
`games.cafecito.foundry.generated`, and other annotated extension classes. Unsupported, ambiguous,
or incorrectly placed declarations stop javac with a diagnostic at the offending annotation,
member, or parameter.

## Initialization order

`@FoundryInitialization` defaults to `SCENE` and may select `CORE`, `SERVERS`, `SCENE`, or `EDITOR`.
Its `after` member names annotated classes that must register first:

```java
@FoundryInitialization(
        value = InitializationLevel.SCENE,
        after = PhysicsServices.class)
```

Every dependency must be an extension class in the compilation contract. Dependency cycles are
compile errors and identify each participating initialization declaration.

## Generated contract

The processor emits one direct-call trampoline next to each extension class and exactly one
stable-sorted registry for the consumer module. It also emits an immutable module descriptor at
`META-INF/foundry-java/modules/<module>.descriptor` and narrow, exact-class keep rules at
`META-INF/proguard/foundry-java-<module>.pro`.

Registration uses direct constructor, method, override, and accessor calls. The registry passes the
binding context and object lease through its generated access contract, and normal
`FoundryBindingContext.bind` lifecycle checks reject stale contexts before an extension factory is
invoked. Registration has no runtime reflection, classpath scanning, Android manifest discovery,
`Class.forName` lookup, or reflective member enumeration. Generated entry points are the only
dynamically retained classes.

The annotations artifact is platform-neutral Java metadata. It has no Android, JNI, or native
dependency. Extension code consumes only the public FoundryExtension-facing API; Foundry-Java never
packages, links, loads, or redistributes `libfoundry_android.so`.
