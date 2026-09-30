# Micronaut CDI

An implementation of the
[Jakarta Contexts and Dependency Injection 4.0](https://jakarta.ee/specifications/cdi/4.0/jakarta-cdi-spec-4.0)
**Lite** specification built on the compile-time dependency injection of Micronaut.

A bean of the specification is read as the Micronaut bean it corresponds to while it is compiled: the scope it
declares becomes the Micronaut scope of the same meaning, a producer becomes a factory method, a disposer is
resolved to the method that will be invoked, and Micronaut generates the bean definition. There is no deployment
step and no scanning of classes at startup, and by default nothing about a bean or an event is resolved by
reflection: a bean's types and qualifiers, what an observer observes, and how the class of an event relates to
the types above it are read from what the processor recorded. The one member the container invokes reflectively
is a private producer or observer, which the specification allows and Micronaut marks `@ReflectiveAccess`. The
parts of the specification's API that hand out reflection objects are an optional module, described under
[Reflection](#reflection).

The interception of a bean is deferred to
[Micronaut Jakarta Interceptors](https://github.com/dstepanov/micronaut-jakarta-interceptors), which implements
the specification that this one defers to.

## Example

```java
@ApplicationScoped
public class Greeter {

    @Inject
    Translator translator;

    @Inject
    Event<Greeted> greeted;

    public String greet(String name) {
        greeted.fire(new Greeted(name));
        return translator.translate("Hello") + " " + name;
    }

    void onStartup(@Observes Startup startup) {
        // the container is ready
    }
}

@ApplicationScoped
public class Connections {

    @Produces
    @Dependent
    Connection connection() {
        return DriverManager.getConnection(url);
    }

    void close(@Disposes Connection connection) throws SQLException {
        connection.close();
    }
}
```

## Modules

| Module | What it is |
| --- | --- |
| `micronaut-cdi` | The runtime: the contexts of the scopes, and the parts of the container a bean can reach |
| `micronaut-cdi-processor` | The annotation processor that reads the specification's annotations while a bean is compiled |
| `micronaut-cdi-tck` | The scenarios of the specification's technology compatibility kit, compiled and exercised here |
| `micronaut-cdi-tck-lang-model` | The kit's language model assertions, verified by a build compatible extension as the kit compiles |
| `test-suite-kotlin` | A Kotlin class compiled through KSP, read by a build compatible extension through the same language model |
| `test-suite-groovy` | The same, for a Groovy class compiled by the Groovy compiler |
| `test-suite-no-reflection` | What works with `micronaut-cdi` alone, and what names the module to add when it does not |
| `micronaut-cdi-reflection` | Optional. Answers the parts of the specification's API that hand out reflection objects, which is the one thing here that reads a class back |

## Build compatible extensions

A build compatible extension goes on the annotation processor path beside `micronaut-cdi-processor`: every phase
of it runs while the application compiles, `@Synthesis` and `@Validation` included. A synthetic bean or observer
is written as a bean of the creator or observer class the extension named, declared by a generated factory, in a
Java, a Groovy or a Kotlin compilation alike, and a problem an
extension reports fails the compilation. The running application needs neither the extension nor
`micronaut-cdi-reflection` for any of it; the classes the extension names - the implementation class of a
synthetic bean, its creator and disposer, a synthetic observer, a context - have to be on the classpath the
application is compiled against. An extension that is only on the runtime classpath does nothing.

## Reflection

With `micronaut-cdi` alone an application has injection and typesafe resolution with any qualifiers, producers and
disposers, the contexts of the scopes, interception, events and observers with parameterized types, `Instance` and
`Event` lookups, the `BeanContainer`, and everything a build compatible extension registered.

Nothing in `micronaut-cdi` reads a class back at runtime to work out what a bean is: that was decided while the
bean was compiled. What cannot be answered that way is the API a program calls that is written in terms of
reflection, and it is kept out of `micronaut-cdi`, in `micronaut-cdi-reflection`, so that an application only
reads classes back if it asks to:

- `InjectionPoint.getMember()`, `getAnnotated()` and, for a field, `isTransient()`;
- an annotation instance of a type the specification has no literal for: what `Bean.getQualifiers()`,
  `InjectionPoint.getQualifiers()`, `ObserverMethod.getObservedQualifiers()`, `EventMetadata.getQualifiers()` and
  `Interceptor.getInterceptorBindings()` report for an annotation of the application's own, and an annotation
  parameter of a synthetic component asked for as an annotation;
- an annotation literal with members handed to `select(...)`, `getBeans(...)`, `resolveObserverMethods(...)` or
  `resolveInterceptors(...)`: its members can only be read reflectively;
- `BeanContainer.isScope`, `isNormalScope`, `isQualifier`, `isStereotype` and `isInterceptorBinding` for an
  annotation the build recorded nothing of;
- `BeanManager.getStereotypeDefinition` and `getInterceptorBindingDefinition`;
- the generic hierarchy of a class no compilation with this processor has seen, for the bean types of a bean of it
  and for matching an event of it against a parameterized observed type.

Each goes through one interface, `CdiReflection`, which `micronaut-cdi-reflection` implements. Without the module
the call throws an `UnsupportedOperationException` that names it.

Resolution itself makes no annotation instance and reads none. A qualifier or an interceptor binding is compared
as the values it was compiled with, less the members marked `@Nonbinding`; injecting a bean, an `Event`, an
`Instance` or an `InjectionPoint`, firing an event and resolving its observers, and binding an interceptor need
nothing but `micronaut-cdi`, whatever qualifiers the application declares. What the container has to know of a
qualifier or binding type - its members, which are non-binding, whether it is repeatable - is recorded by the
processor under `META-INF/micronaut-cdi/bindings`, for every such type a compilation declares or uses. The
qualifiers of the specification - `@Default`, `@Any`, `@Named` - and a literal of a qualifier that has no binding
member are taken as they are, with nothing read.

### Selecting without reflection

The lookups and events this container hands out are Micronaut types that extend the specification's, and select
by Micronaut's own forms of an annotation and of a type:

| Specification | Micronaut | Adds |
| --- | --- | --- |
| `Instance<T>` | `io.micronaut.cdi.MicronautInstance<T>` | `select(AnnotationValue, AnnotationValue...)`, `select(Class, AnnotationValue, AnnotationValue...)`, `select(Argument, AnnotationValue...)` |
| `Event<T>` | `io.micronaut.cdi.MicronautEvent<T>` | the same three selections |
| `BeanContainer` | `io.micronaut.cdi.MicronautBeanContainer` | `getBeans(Argument, AnnotationValue, AnnotationValue...)` - and `getBeans(Type, Annotation...)` takes an `Argument` as the type - `resolveObserverMethods(event, AnnotationValue, AnnotationValue...)`, and `createInstance()` / `getEvent()` returning the Micronaut types |

```java
@Inject MicronautInstance<Dish> dishes;
@Inject MicronautEvent<Order> orders;

Dish sweet = dishes.select(AnnotationValue.builder(Flavour.class).value("sweet").build()).get();
orders.select(AnnotationValue.builder(Flavour.class).value("sweet").build()).fire(new Order("tart"));
```

An injection point may be declared with the Micronaut type, and an injected `Instance` or `Event`,
`CDI.current()`, an `SeContainer` and the `BeanContainer` may be cast to it. A selection by `AnnotationValue` is
held to the rules of the specification's own - the annotation has to be a qualifier, and one that is not
repeatable is given once - checked from what was recorded. It selects the same beans and notifies the same
observers as the literal does where the reflection module is there to read the literal. `select(Argument, ...)`
is the counterpart of `select(TypeLiteral, ...)`.

### Types without reflection

The bean types of a bean, what an observer observes and the generic hierarchy of every class a compilation
compiles are recorded by the processor, so a parameterized injection point or lookup, and an event, are matched
by their type arguments with nothing read from a class:

- a bean's type closure is recorded on the bean, and on the producer that produces it;
- an observed type is recorded on the observer, with its wildcards and type variables;
- the closure of each compiled class that has a parameterized type above it, or declares type variables, is
  recorded on a class generated in its package. An event of `OrderCreated implements DomainEvent<Order>` is
  observed by an observer of `DomainEvent<Order>` and not by one of `DomainEvent<Invoice>`, however it was
  fired, and firing an object of a generic class through an event that leaves its type variables unresolved is
  the `IllegalArgumentException` of the specification.

An event fired through `MicronautEvent.select(Argument)` is an event of exactly the type given, which is how an
object of a generic class is fired with its type stated in full:

```java
events.select(Argument.of(Box.class, String.class)).fire(new Box<>("text"));
```

What is left to `micronaut-cdi-reflection` is a class no compilation with this processor has seen: a bean
compiled without it is resolvable by its class and the raw types Micronaut exposes it as, and an event of such a
class is matched as the type it was fired as where that names its class, and as its raw class otherwise - so an
observer of a parameterized supertype of it is only notified where the module is there to read the class, or
where the event is fired through `select(Argument)`.

That module also brings `io.micronaut:micronaut-reflection`, which answers the accessors of an interceptor's
`InvocationContext` that return an object of the Java reflection API: `getMethod()`, `getConstructor()`,
`getInterceptorBindings()`, `getInterceptorBinding(Class)` and `getInterceptorBindings(Class)`. Without it each of
them throws an `UnsupportedOperationException` that names the dependency to add, and everything else of an
interception works as before.

The boundary is checked while the modules compile, by the `NoReflection` check of
[errorprone-no-reflection](https://github.com/micronaut-projects/errorprone-no-reflection), alongside NullAway. The
check matches the method a call resolves to and names the kind of reflection it reaches for. The processor and
`micronaut-cdi-reflection` are allowed all of it. In `micronaut-cdi` and `micronaut-cdi-el` no class or package is
allowed any, and no call is suppressed in the source. What `micronaut-cdi` allows is a list of calls, none of which
looks anything up on a class: the accessors of a `java.lang.reflect.Type` that was handed in - the raw type and
arguments of a `ParameterizedType`, the bounds of a `TypeVariable` and of a `WildcardType`, the component of a
`GenericArrayType` - since the specification's interfaces are written in that type, and the constructors of the
container's own implementations of those interfaces. Inside the container a type is a Micronaut `Argument`: a
`Type` is read into one where a program hands it in and made from one where the specification reports it, both
in the package `io.micronaut.cdi.runtime.type`. The list is in [cdi/build.gradle](cdi/build.gradle).
Reflection anywhere else fails the build.

## Conformance

What is implemented, and every place where this module differs from the specification, is recorded in
[CONFORMANCE.md](CONFORMANCE.md). A difference is recorded there, and a test of the kit it rules out is named there rather than left
out, so that what is not covered is as visible in a test report as what is.

## Building

The build takes [Micronaut Jakarta Interceptors](https://github.com/dstepanov/micronaut-jakarta-interceptors)
at its `main` branch as a Gradle [source dependency](https://blog.gradle.org/introducing-source-dependencies):
`settings.gradle` maps its modules to its Git repository, and Gradle checks the repository out under
`.gradle/vcs-1/` and builds it as part of this build. The branch is what selects the source, not the version the
catalog names; the catalog version is what this project's POMs and BOM are published with. A build that resolves
it fetches the head of the branch, so the network is needed; `--offline` builds what is already checked out.

[Micronaut Jakarta EL](https://github.com/micronaut-projects/micronaut-jakarta-el) is the release the catalog
names, from Maven Central.

To build against a checkout of your own of either, name its directory. It is then an included build, which takes
the place of the repository or of the release:

```
./gradlew build -PjakartaInterceptorsDir=../micronaut-jakarta-interceptors -PjakartaElDir=../micronaut-jakarta-el
```

Micronaut itself comes from Maven Central and, while this builds on a snapshot of Micronaut Core, from the
snapshot repository. The local Maven repository is not consulted: a stale local publication of a snapshot would
shadow the published one.

`./gradlew fetchSpec` downloads the specification the implementation is read against; it is not kept in this
repository.
