# Authoring Java extensions

Foundry-Java registers extension code at compile time. Apply the annotation processor to each
consumer module and give that module a stable lowercase identity:

```text
-Afoundry.module=my-gameplay
```

The identity becomes part of the generated registry class and descriptor path. Changing it is an
artifact compatibility change.

## Extension declarations

An extension is a public, final, top-level class with a public zero-argument constructor. Its direct
Java superclass must match the `base` named by `@FoundryClass`:

```java
@FoundryClass(base = Node3D.class)
@FoundryInitialization(InitializationLevel.SCENE)
public final class SpinningCube extends Node3D {
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
    public void _process(double delta) {
        rotateY(delta * speed);
    }

    @FoundrySignal
    public interface ResetDone {
        void emitted(double previousSpeed);
    }
}
```

`@FoundryClass`, `@FoundryMethod`, `@FoundryProperty`, `@FoundrySignal`, and
`@FoundryOverride` accept an optional exported `name`. Empty names use the Java declaration name.
All exported names in one class share a namespace and must be unique.

An exported method or virtual override must be a public instance method without Java type
parameters or checked exceptions. `@FoundryOverride` must exactly match a method on the declared
engine base, including its return and parameter types. A property annotation belongs on a field;
its public getter returns exactly the field type and its optional public setter accepts exactly that
type. A signal annotation belongs on a nested interface with exactly one abstract, `void` method.

Supported callback types are Java primitives, `void` returns, `String`, enums, generated types below
`games.cafecito.foundry.api` or `games.cafecito.foundry.types`, and other annotated extension
classes. Unsupported, ambiguous, or incorrectly placed declarations stop javac with a diagnostic at
the offending annotation, member, or parameter.

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

Registration uses direct constructor, method, override, and accessor calls. Registration has no
runtime reflection, classpath scanning, Android manifest discovery, `Class.forName` lookup, or
reflective member enumeration. Generated entry points are the only dynamically retained classes.

The annotations artifact is platform-neutral Java metadata. It has no Android, JNI, or native
dependency. Extension code consumes only the public FoundryExtension-facing API; Foundry-Java never
packages, links, loads, or redistributes `libfoundry_android.so`.
