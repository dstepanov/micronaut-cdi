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

| `micronaut-cdi-reflection` | Optional. Describes to a build compatible extension the beans the compiler never saw, which is the one thing here that reads a class back |

A build compatible extension goes on the annotation processor path beside `micronaut-cdi-processor`, since it
runs while the classes it enhances are compiled.

Nothing in `micronaut-cdi` reads a class back at runtime to work out what a bean is: that was decided while the
bean was compiled. The one part of the specification that cannot be answered that way is kept out of it, in
`micronaut-cdi-reflection`, so that an application only reads classes back if it asks to.

## Conformance

What is implemented, and every place where this module differs from the specification, is recorded in
[CONFORMANCE.md](CONFORMANCE.md). A difference is recorded there and marked by a disabled test rather than left
out, so that what is not covered is as visible in a test report as what is.

## Building

The build resolves [Micronaut Jakarta Interceptors](https://github.com/dstepanov/micronaut-jakarta-interceptors)
from a checkout beside this one when there is one, since it has no published snapshot yet:

```
git clone https://github.com/dstepanov/micronaut-jakarta-interceptors ../micronaut-jakarta-interceptors
./gradlew build
```

The local Maven repository is consulted before the snapshot repository, so a Micronaut built from a checkout
beside this one and published with `publishToMavenLocal` is what this builds against. That is how a fix being
worked on in Micronaut itself is built against here before it is released; where nothing has been published
locally, the published snapshot resolves as usual.

`./gradlew fetchSpec` downloads the specification the implementation is read against; it is not kept in this
repository.
