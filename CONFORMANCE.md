# Conformance

What this module implements of
[Jakarta Contexts and Dependency Injection 4.0](https://jakarta.ee/specifications/cdi/4.0/jakarta-cdi-spec-4.0),
section by section of Part I, chapter 2 — CDI Lite. Only CDI Lite is in scope; CDI Full (chapter 3) is not
implemented and is not claimed.

Every difference is recorded here and marked by a disabled test that names this file, so that what is not covered
is as visible in a test report as what is.

## Implemented

| Section | What | Where |
| --- | --- | --- |
| 2.1.4, 2.5.6 | The dependent pseudo-scope, read as the Micronaut prototype scope | `DependentAnnotationMapper` |
| 2.1.4, 2.5.6 | The application scope, read as a proxied Micronaut scope | `ApplicationScope`, `CdiApplicationScope` |
| 2.1.4, 2.5.6 | The request scope, and the `ContextNotActiveException` of reaching for it outside a request | `RequestScope`, `CdiRequestScope` |
| 2.1.3, 2.2.8 | Qualifiers, and the rule that a bean declaring none has the default qualifier | `DefaultQualifierVisitor` |
| 2.2.2, 2.2.3 | Producer methods and producer fields, read as Micronaut factory methods and fields | `ProducerVisitor` |
| 2.2.4 | Disposer methods, resolved to their producer while it is compiled | `ProducerVisitor`, `DisposerInvoker` |
| 2.2.5, 2.2.6, 2.2.7 | Bean constructors, injected fields and initializer methods | Micronaut's own injection |
| 2.1.2 | Narrowing the bean types of a bean with `@Typed` | `TypedAnnotationMapper` |
| 2.7 | Interceptor bindings | deferred to Micronaut Jakarta Interceptors |
| 2.4.6 | Programmatic lookup through `Instance`, including handles | `CdiInstance` |
| 2.9.1 | The `BeanContainer`, and the `Bean` a lookup resolves to | `CdiBeanContainer`, `CdiBean` |
| 2.9.1 | The `BeanManager` of CDI Full, as far as CDI Lite can answer it, and injectable as a bean | `CdiBeanContainer` |
| 2.9.1 | `CDI.current()`, found through the service loader | `MicronautCDIProvider`, `MicronautCDI` |
| 2.5.2 | The `Context` of each scope, and whether it is active | `CdiContext` |
| 2.4.2 | The rules of resolution applied to types and qualifiers on their own | `CdiAssignability` |
| 2.8.2 | Firing an event, synchronously and asynchronously, and narrowing one with `select` | `CdiEvent` |
| 2.8.3 | Observer resolution by the event's type and qualifiers | `ObserverRegistry`, `CdiAssignability` |
| 2.8.4 | Observer methods, including static ones, `Reception.IF_EXISTS` and `@Priority` | `ObserverVisitor`, `CdiObserverMethod` |
| 2.8.5 | Observer notification, in ascending order of priority | `ObserverRegistry` |
| 2.8.6 | The container lifecycle events: `Startup`, `Shutdown`, `@Initialized`, `@BeforeDestroyed`, `@Destroyed` | `ContainerLifecycle` |
| 2.11.5 | `@Vetoed`, on a class and on a package | `BeanDiscoveryVisitor` |
| 2.1.7 | Alternatives, selected by `@Priority`, replacing the beans they are an alternative to | `BeanDiscoveryVisitor` |
| 2.1.8 | Stereotypes, which carry the scope, qualifiers, name and alternative status they declare | Micronaut's meta-annotations |
| 2.1.2 | The bean types of a bean, including a produced array, interface and primitive, and narrowing with `@Typed` | `CdiBean` |
| 2.1.2 | A primitive and the class that boxes it as one bean type | `CdiTypes`, `CdiInstance`, `CdiBeanContainer` |
| 2.2.5 | A managed bean has a constructor taking no parameters or one annotated `@Inject` | `BeanDiscoveryVisitor` |
| 2.4.2 | Resolution by every qualifier named, with `@Nonbinding` members left out of the comparison | `CdiQualifiers`, `CdiAnnotations` |
| 2.10.3 | The `@Enhancement` phase of a build compatible extension, and the language model it reads | `BuildCompatibleExtensionVisitor`, `io.micronaut.cdi.processor.extension` |
| 2.10.2 | The `@Discovery` phase, registering an annotation as a qualifier, an interceptor binding or a stereotype | `DiscoveredClasses` |
| 2.10.5 | The `@Synthesis` phase, and the synthetic beans it describes | `SynthesisRunner`, `io.micronaut.cdi.runtime.extension` |
| 2.10.6 | The `@Validation` phase, whose errors stop the container from starting | `SynthesisRunner` |
| 2.10.4 | The `@Registration` phase, run over each bean and observer as it is compiled | `ElementBeanInfo`, `ElementObserverInfo`, `BuildCompatibleExtensionVisitor` |
| 2.10.5 | Synthetic beans and synthetic observers, with the creation and disposal functions and the lookup they are handed | `SynthesisRunner`, `SyntheticObserverMethod` |
| 2.10.1 | `ScannedClasses.add` and `MetaAnnotations.addContext`, applied while the classes are compiled | `DiscoveredClasses`, `ExtensionContexts` |
| 5 (SE) | The SE bootstrap: `SeContainerInitializer` through the service loader, `SeContainer` over a Micronaut context, discovery turned off as a narrowed one | `MicronautSeContainerInitializer`, `MicronautSeContainer` |
| 2.1.7 (SE) | An alternative no priority selected, enabled by `selectAlternatives`/`selectAlternativeStereotypes` as the container is built | `CdiSelectableAlternative`, `UnselectedAlternative` |
| 2.9.2 | `@ActivateRequestContext` as the built-in Jakarta interceptor at `PLATFORM_BEFORE + 100`, so the application's interceptors stand on either side of it | `ActivateRequestContextJakartaInterceptor` |
| 2.5.6 | The request context active during any bean's `@PostConstruct` and during asynchronous observer notification, and its `@Initialized`/`@BeforeDestroyed`/`@Destroyed` events | `RequestScope`, `ObserverRegistry` |
| 7 (4.1) | Method invokers: `InvokerFactory` in the registration phase, validated as the bean compiles, invoking the compiled executable method at runtime with the instance and argument lookups of the specification | `ElementInvokerFactory`, `RecordedInvoker` |

## Not yet implemented

The transaction phases an observer may name (2.8.4) have no transactions to observe here and are notified as
if `IN_PROGRESS`.

`BeanContainer.resolveInterceptors` resolves the interceptor classes the Jakarta Interceptors processor compiled:
an interceptor is enabled by the priority it declares, bound when every binding it declares is among the given
ones, and the resolved list is ordered lowest priority first. The interception of a bean still happens where it
was compiled; what the manager adds is the description of it the specification asks for, including invoking an
interceptor directly through `Interceptor.intercept`.

## Differences

### The default qualifier is given to the bean rather than to the injection point

Section 2.2.8 has an injection point that declares no qualifier looking for the default qualifier, and section
2.1.3 has a bean that declares no qualifier having it. This module writes the second half of that rule onto the
bean and leaves the first half to Micronaut, which resolves an injection point that names no qualifier to the
primary bean of the type when there is more than one candidate; the bean given the default qualifier is declared
primary, so the same bean is resolved.

The difference this leaves is at an injection point that names no qualifier where every candidate is qualified:
the specification has no bean to resolve, and Micronaut resolves one. Writing the rule onto injection points
instead would make a bean of this specification unable to be injected with a bean that is not one, which is the
worse of the two.

### An unproxyable normal scoped bean is rejected as it is compiled

*Section 2.2.10.* A bean in a normal scope has to be proxyable, and the specification has the container detect a
bean that is not: a final class, a class with a final method, a primitive, an array. This module detects them, and
reports them through the compiler rather than as a deployment is assembled — which is the same detection at an
earlier moment. The kit's deployments that exist to be rejected are excluded from the scenarios compiled here for
that reason, listed by name in `cdi-tck/build.gradle`.

### A private producer or observer is read reflectively

*Sections 2.2.2, 2.2.3 and 2.8.4.* The specification allows a producer method, a producer field and an observer
method to be private, and a private member cannot be read from the bean definition Micronaut generates beside it.
Such a member is annotated `@ReflectiveAccess`, which is Micronaut's way of saying that it is read reflectively,
and only that member is: everything else about the bean goes on being resolved the way it was compiled. What the
author wrote therefore decides where reflection is used, rather than the module deciding it for them.

### A primitive is boxed by the lookup rather than by the bean

*Section 2.1.2.* A primitive type and the class that boxes it are one bean type. Micronaut resolves a bean by the
type it was written as and keeps the two apart, so a lookup made through this module is made for both and what
they resolve is put together. An injection point of a plain Micronaut bean is not rewritten, so it goes on
resolving the way it did: a field of `Double` injected into one does not resolve a producer of `double`.

### The phases of an extension run at the moments they are about

*Section 2.10.* Discovery, enhancement and registration run while the classes are compiled, which is where the
classes are; synthesis and validation run as the container starts, which is where the beans are. An extension
therefore goes on the annotation processor path of the project it enhances rather than on its classpath, and is
still found through the service loader as the specification says.

What the discovery phase says is recorded by name and applied as the named classes come past the compiler.
`ScannedClasses.add` writes a generated `@ClassImport` source so that a class that says nothing at all about
itself is still compiled into a bean; `MetaAnnotations.addContext` records the scope and its context classes,
which the runtime reads to instantiate the contexts and serve the scope. An annotation registered as a
qualifier, an interceptor binding or a stereotype is one only if it is compiled by the same build.

The registration phase describes each bean as it is compiled, and so does not describe a bean the compiler never
saw. Those are the synthetic ones, and describing them means reading their classes back: that is
`micronaut-cdi-reflection`, a module an application adds when it wants it. Without it a synthetic bean is simply
not described; every bean that was compiled still is.

### The bean manager answers what CDI Lite can

*Section 2.9.1.* The programmatic access CDI Lite describes is the `BeanContainer`. The `BeanManager` of CDI Full
extends it, and is implemented here as far as Lite reaches: looking a bean up, resolving an injectable reference,
comparing two qualifiers or two interceptor bindings by the members that bind, and reading the definition of a
stereotype or an interceptor binding. It is a bean, so a program can inject it.

What belongs to CDI Full says so rather than answering: decorators, passivation, portable extensions, and
building a bean out of an annotated type. The expression language is the one named exception, provided beyond
Lite by the optional `micronaut-cdi-el` module over `micronaut-jakarta-el`: with it on the classpath,
`getELResolver` answers with a resolver in which a name at the base of an expression is the bean of that name,
and `wrapExpressionFactory` wraps a factory so that what it creates evaluates with the container's beans in
reach; without it, both say the module is missing. The manager is implemented because a program
written against the specification reaches for it — the kit's own tests do — not because CDI Full is claimed.

### A producer compiles wherever it is declared

*Section 2.2.2 / annotated discovery.* Under Lite's annotated discovery a class with no bean defining
annotation is not a bean, and a producer it declares is inert. Here the producer is compiled regardless,
because bean-archive membership is a per-deployment question a global compilation cannot answer — the SE
bootstrap's {@code addBeanClasses} makes exactly such a class a bean by fiat. A producer in a class no
deployment ever admits is the difference visible to code that counts beans.

### A qualifier written more than once on a parameter is not read

*Section 2.1.3.* A `@Repeatable` qualifier is recorded by the compiler in its container annotation, and
Micronaut keeps that container in the metadata of a field, a class and a method — but not of a *parameter*,
where neither the qualifier nor its container survives. An observer method written
`void on(@Observes @Start("A") Event e)` therefore reads as having no qualifiers, and is notified of every
event of its type. The limitation is the container's, not this module's: the annotation is gone before any
visitor runs. Repeatable qualifiers on beans, producers and fields work. The kit's
`RepeatableQualifiersTest` is excluded by name for this reason.

### Known limitations a review has named

Three findings of an internal review are documented rather than coded around. A dependent bean reached from an
EL expression through `getELResolver` is created but not destroyed when the evaluation completes — the EL
contract offers the resolver no end-of-evaluation moment to hook; EL is provided beyond Lite, and a program
that needs the destruction can look the bean up and destroy it itself. Ending a request begun with
`RequestContextController.activate()` from a different thread than began it silently does nothing — the
controller's bookkeeping is per-thread, as the specification's enter-and-exit shape assumes; the `run`/`supply`
/`call` forms are safe across threads. And two beans in different normal scopes whose creations reach into each
other's scope concurrently can, in principle, deadlock on the two contexts' locks — creation runs under the
scope's lock, which is also what guarantees one instance per context.

## The technology compatibility kit

The `micronaut-cdi-tck` module resolves `jakarta.enterprise:cdi-tck-core-impl` from Maven Central at build time,
unpacks the CDI Lite scenarios — the beans, producers and qualifiers the specification's own authors wrote —
and compiles them with this module's annotation processor. Nothing is vendored and nothing is modified.

The kit's own unmodified test classes run here, through a purpose-built Arquillian container adapter
(`io.micronaut.cdi.tck.arquillian`). Each test's deployment archive becomes one `ApplicationContext` narrowed to
the archive's classes; a deployment the kit expects to be rejected is compiled per-deployment with the module's
processor, and what the compiler refuses is reported to Arquillian as the `DefinitionException` or
`DeploymentException` the test asserts — deployment here *is* compilation. An archive carrying a build
compatible extension is likewise compiled per deployment, with that archive's extensions alone.

Six SE bootstrap tests are left out by name, each resting on what belongs to CDI Full and is refused rather
than pretended here: `BootstrapSEContainerTest`'s `testAddExtensionAsExtensionInstance`, `testAddExtensionAsClass`
(portable extensions) and `testAddDecorator` (decorators); `CustomClassLoaderSETest` and
`CustomRequestContextSETest` (portable extensions registered through the deployment); and
`TrimmedBeanArchiveSETest` (`InterceptionFactory`).

The `tckSuite` task runs the suite of `tck-suite.xml`: the whole of the kit's CDI Lite `tests/**` packages —
the SE bootstrap and the CDI 4.1 invokers included — together with the Jakarta Interceptors kit
(`interceptors/tests/**`): 771 tests, all passing, and part of `check`. A handful of ported assertions and
`ScenarioSweepTckTest` — which reads every scenario bean through one container at once — remain as local
regression tests beside the kit's own.
