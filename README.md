# Micronaut CDI

An implementation of the
[Jakarta Contexts and Dependency Injection 4.0](https://jakarta.ee/specifications/cdi/4.0/jakarta-cdi-spec-4.0)
**Lite** specification built on the compile-time dependency injection of Micronaut.

A bean of the specification is read as the Micronaut bean it corresponds to while it is compiled: the scope it
declares becomes the Micronaut scope of the same meaning, a producer becomes a factory method, a disposer is
resolved to the method that will be invoked, and Micronaut generates the bean definition. There is no deployment
step and no scanning at startup, and nothing about a bean is resolved by reflection.

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

A build compatible extension goes on the annotation processor path beside `micronaut-cdi-processor`: every phase
of it runs while the application compiles, `@Synthesis` and `@Validation` included. A synthetic bean or observer
is written as a bean of the creator or observer class the extension named, declared by a generated factory, in a
Java, a Groovy or a Kotlin compilation alike, and a problem an
extension reports fails the compilation. The running application needs neither the extension nor
`micronaut-cdi-reflection` for any of it; the classes the extension names - the implementation class of a
synthetic bean, its creator and disposer, a synthetic observer, a context - have to be on the classpath the
application is compiled against. An extension that is only on the runtime classpath does nothing.

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
- `BeanManager.getStereotypeDefinition` and `getInterceptorBindingDefinition`.

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

That module also brings `io.micronaut:micronaut-reflection`, which answers the accessors of an interceptor's
`InvocationContext` that return an object of the Java reflection API: `getMethod()`, `getConstructor()`,
`getInterceptorBindings()`, `getInterceptorBinding(Class)` and `getInterceptorBindings(Class)`. Without it each of
them throws an `UnsupportedOperationException` that names the dependency to add, and everything else of an
interception works as before.

The boundary is checked while the modules compile, by the `NoReflection` check of
[errorprone-no-reflection](https://github.com/micronaut-projects/errorprone-no-reflection), alongside NullAway. The
check matches the method a call resolves to and names the kind of reflection it reaches for. The processor and
`micronaut-cdi-reflection` are allowed all of it. In `micronaut-cdi`, each class is allowed only the kinds the
specification's own interfaces put there, such as the `java.lang.reflect.Type` of a bean type, the annotation instance
of a qualifier. The phases of a build compatible extension need none, since they run while the application compiles.
The list, with the reason for each entry, is in [cdi/build.gradle](cdi/build.gradle). Reflection anywhere else fails
the build.

## Conformance

What is implemented, and every place where this module differs from the specification, is recorded in
[CONFORMANCE.md](CONFORMANCE.md). A difference is recorded there and marked by a disabled test rather than left
out, so that what is not covered is as visible in a test report as what is.

## Building

The build includes [Micronaut Jakarta Interceptors](https://github.com/dstepanov/micronaut-jakarta-interceptors)
at its `main` branch and [Micronaut Jakarta EL](https://github.com/micronaut-projects/micronaut-jakarta-el) at its
`1.1.x` branch as composite builds. The first build clones each into `.included-builds/`, and it is left as it is
afterwards, so `git -C .included-builds/<name>-<branch> pull` brings one up to date. To build against a checkout of your own,
name its directory:

```
./gradlew build -PjakartaInterceptorsDir=../micronaut-jakarta-interceptors -PjakartaElDir=../micronaut-jakarta-el
```

Micronaut itself comes from Maven Central. The local Maven repository is consulted first, so a Micronaut built from a
checkout beside this one and published with `publishToMavenLocal` is what this builds against. That is how a fix
being worked on in Micronaut itself is built against here before it is released.

`./gradlew fetchSpec` downloads the specification the implementation is read against; it is not kept in this
repository.
