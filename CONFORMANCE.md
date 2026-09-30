# Conformance

What this module implements of
[Jakarta Contexts and Dependency Injection 4.0](https://jakarta.ee/specifications/cdi/4.0/jakarta-cdi-spec-4.0),
section by section of Part I, chapter 2 — CDI Lite. Only CDI Lite is in scope; CDI Full (chapter 3) is not
implemented and is not claimed.

Every difference is recorded here. No test is disabled for one: a test of the kit that a difference rules out is
left out by name, in `cdi-tck/build.gradle` and `tck-suite.xml`, and is named below, and the two sections of the
language model kit that are not passed run as skipped tests that say what each waits on, so that those are as
visible in a test report as what passes.

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
| 2.9.1.10 | Interceptor resolution through the bean container | `CdiBeanContainer` |
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
| 2.4.2 | Resolution by every qualifier named, with `@Nonbinding` members left out of the comparison, on the values each qualifier was compiled with | `CdiQualifier`, `CdiQualifiers`, `BindingTypes`, `BindingTypeVisitor` |
| 5.6.1, 2.8 | `Instance` and `Event` selected by `AnnotationValue` and `Argument`, beside the specification's selections | `MicronautInstance`, `MicronautEvent`, `MicronautBeanContainer` |
| 2.10.3 | The `@Enhancement` phase of a build compatible extension, and the language model it reads | `BuildCompatibleExtensionVisitor`, `io.micronaut.cdi.processor.extension` |
| 2.10.2 | The `@Discovery` phase, registering an annotation as a qualifier, an interceptor binding or a stereotype | `DiscoveredClasses` |
| 2.10.5 | The `@Synthesis` phase, run once the classes of a compilation have been registered, and the synthetic beans and observers it describes, each written as a generated bean definition | `BuildCompatibleExtensionVisitor`, `SynthesisPhase` |
| 2.10.6 | The `@Validation` phase, whose errors fail the compilation | `BuildCompatibleExtensionVisitor` |
| 2.10.4 | The `@Registration` phase, run over each bean and observer as it is compiled, and over the synthetic beans and observers and the built-in beans once synthesis has run | `ElementBeanInfo`, `ElementObserverInfo`, `SyntheticBeanInfo`, `SyntheticObserverInfo`, `BuildCompatibleExtensionVisitor` |
| 2.10 | `AnnotationBuilder` and `Types`, composing annotation values and types of the one language model | `ElementAnnotationBuilder`, `ElementBuildServices`, `VisitorTypes` |
| 2.10.5 | Synthetic beans and synthetic observers at runtime, registered from what was recorded, with the creation and disposal functions, the parameters and the lookup they are handed | `RecordedSynthesis`, `CdiParameters`, `SyntheticObserverMethod` |
| 2.10.1 | `ScannedClasses.add` and `MetaAnnotations.addContext`, applied while the classes are compiled | `DiscoveredClasses`, `ExtensionContexts` |
| 5 (SE) | The SE bootstrap: `SeContainerInitializer` through the service loader, `SeContainer` over a Micronaut context, discovery turned off as a narrowed one | `MicronautSeContainerInitializer`, `MicronautSeContainer` |
| 2.1.7 (SE) | An alternative no priority selected, enabled by `selectAlternatives`/`selectAlternativeStereotypes` as the container is built | `CdiSelectableAlternative`, `UnselectedAlternative` |
| 2.9.2 | `@ActivateRequestContext` as the built-in Jakarta interceptor at `PLATFORM_BEFORE + 100`, so the application's interceptors stand on either side of it | `ActivateRequestContextJakartaInterceptor` |
| 2.5.6 | The request context active during any bean's `@PostConstruct` and during asynchronous observer notification, and its `@Initialized`/`@BeforeDestroyed`/`@Destroyed` events | `RequestScope`, `ObserverRegistry` |
| 7 (4.1) | Method invokers: `InvokerFactory` in the registration phase, validated as the bean compiles, invoking the compiled executable method at runtime with the instance and argument lookups of the specification | `ElementInvokerFactory`, `RecordedInvoker` |

## Not yet implemented

The transaction phases an observer may name (2.8.4) have no transactions to observe here and are notified as
if `IN_PROGRESS`.

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

### A qualifier is compared as the values it was compiled with

*Sections 2.2.3, 2.4.2 and 2.8.3.* The specification's interfaces take and report a qualifier as an annotation
instance. The container compares one as the values it was written with: two qualifiers are the same when they
are of one type and their binding members are equal, a member left at its default being equal to the default
written down. Nothing is materialized to resolve a bean, inject an `Event` or an `Instance`, or notify an
observer. An instance is made only when a program asks for one - `Bean.getQualifiers()` and its like - and that
is the reflection module's.

An annotation literal a program hands to `select`, `getBeans` and their like is a reflection object. The
literals of the specification and a literal of a qualifier with no binding member are taken as they are; any
other has to be read member by member, which needs `micronaut-cdi-reflection` and says so. The reflection-free
form of the same selection is an `AnnotationValue`, through `MicronautInstance`, `MicronautEvent` and
`MicronautBeanContainer`.

What the container has to know of a qualifier or interceptor binding type is recorded by the processor as a
resource under `META-INF/micronaut-cdi/bindings`, for every such type a compilation declares or uses. A type no
compilation with this processor has seen has no record, and is asked of its class by the reflection module.

### Bean types, observed types and event types are what the compiler recorded

*Sections 2.2.1, 2.4.2.1 and 2.8.1.* The type closure of a bean, the type an observer observes and the closure
an event is matched by are properties of generic signatures, which the container does not read. The processor
records each: the closure of a bean on the bean or on its producer; the observed type on the observer; and the
closure and the type variables of every class a compilation compiles, on a class it generates in the package of
the classes. Inside the container each of these types is a Micronaut `Argument`; a `java.lang.reflect.Type` is
read into one where a program hands it to the specification's API and made from one where that API reports it,
in `io.micronaut.cdi.runtime.type`. The type of an event is the class of the event object with its type variables resolved from the
type the event was fired as, worked out from that record, and an object whose class leaves a variable
unresolved is refused from it.

A class no compilation with this processor has seen has no record. With `micronaut-cdi-reflection` its generic
signature is read, as the specification assumes. Without it a bean of such a class has its class and the raw
types Micronaut exposes it as for bean types, and an event of such a class is of the type it was fired as where
that names its class and of its raw class otherwise: it is observed by an observer of a raw type or of the type
it was fired as, and not by an observer of another parameterized supertype. `MicronautEvent.select(Argument)`
states the event type in full and needs no record and no module.

### The API written in terms of reflection is an optional module

*Sections 2.4.5.7, 2.4.5.8, 2.4.6, 2.8.4.3, 2.9.1.5 and 2.9.1.9 to 2.9.1.11, and of CDI Full 3.9.1 and 3.9.3.10.*
`micronaut-cdi` reads no class back, and the build holds it to that: the `NoReflection` check allows it nothing
but the accessors of a `java.lang.reflect.Type` it was handed and the making of the value classes of
`io.micronaut.cdi.runtime.type`, which such a type is reported as. The
methods of the specification that return a reflection object, or that can only be answered from one, are
answered by `micronaut-cdi-reflection`, and without it each throws an `UnsupportedOperationException` naming the
module: `InjectionPoint.getMember()`, `getAnnotated()` and `isTransient()`; the qualifiers and interceptor
bindings of a bean, an injection point, an observer or an event reported as annotation instances, for an
annotation that is not one of the specification's; an annotation literal with binding members handed to a
lookup; `isScope`, `isNormalScope`, `isQualifier`, `isStereotype` and `isInterceptorBinding` for an annotation the
build recorded nothing of; `getStereotypeDefinition` and `getInterceptorBindingDefinition`; and the generic
hierarchy of a class no compilation with this processor has seen. Everything else - resolution, injection,
events, interception, the contexts, what an extension registered - needs only `micronaut-cdi`, and
`MicronautInstance`, `MicronautEvent` and `MicronautBeanContainer` give the selections a reflection-free form.

### The application context is destroyed as the context begins to stop

*Sections 2.5.6.2 and 2.8.6.* As a Micronaut context begins to stop, before it destroys any bean, `Shutdown` is
fired, then `@BeforeDestroyed(ApplicationScoped.class)`; the application context is then destroyed - every
application scoped bean, and the dependent objects of each - and `@Destroyed(ApplicationScoped.class)` is fired.
Two things follow from the order. A bean of the singleton pseudo-scope belongs to no context and is destroyed by
Micronaut afterwards, so after `@Destroyed(ApplicationScoped.class)`. And an application scoped bean that
observes `@Destroyed(ApplicationScoped.class)`, or is reached by a singleton as it is destroyed, is a new
instance created for that, and is destroyed when the context has stopped. What an observer of these events
throws is logged and stops neither the events after it nor the context from stopping.

### A primitive is boxed by the lookup rather than by the bean

*Section 2.1.2.* A primitive type and the class that boxes it are one bean type. Micronaut resolves a bean by the
type it was written as and keeps the two apart, so a lookup made through this module is made for both and what
they resolve is put together. An injection point of a plain Micronaut bean is not rewritten, so it goes on
resolving the way it did: a field of `Double` injected into one does not resolve a producer of `double`.

### Every phase of an extension runs while the application compiles

*Section 2.10.* Discovery runs as the compilation starts. Enhancement and registration run as each class is
compiled. Synthesis, the registration of what it described, and validation run once every class of the
compilation has been registered. An extension therefore goes on the annotation processor path of the project it
is written for rather than on its classpath, and is still found through the service loader as the specification
says. The running application does not load it, and an extension that is only on the runtime classpath does
nothing.

What the discovery phase says is recorded by name and applied as the named classes come past the compiler.
`ScannedClasses.add` writes a generated `@ClassImport` source so that a class that says nothing at all about
itself is still compiled into a bean. `MetaAnnotations.addContext` declares a bean of the context
class, recording the scope it serves, and the runtime obtains the context from that definition. An annotation
registered as a qualifier, an interceptor binding or a stereotype is one only if it is compiled by the same build.

A synthetic bean is written as a bean of the creator class the extension named, declared by a generated factory, and the
rest of what the extension said - the bean types, the qualifiers, the scope, the name, the priority, the
parameters - is the annotation metadata of that definition. The disposer class and the observer class of a
synthetic observer get a definition the same way. As the container starts it reads those records, registers a
bean for each synthetic bean, and obtains a creator, a disposer, an observer or a context from its definition:
it loads no extension, invokes nothing reflectively and instantiates no class by name. The parameters an
extension attaches with `withParam` are the types the specification allows, all of which an annotation value
holds. The classes the extension names have to be on the classpath the application is compiled against, and
each needs a constructor the generated definition can call.

The language model an extension reads, and the annotations and types it composes with `AnnotationBuilder` and
`Types`, have one implementation, built on the compiler's view of the classes. A synthetic bean, a synthetic
observer and the built-in beans are described to the registration phase in that model too.

An error an extension reports through `Messages`, or a problem it throws, in synthesis, in the registration of a
synthetic component or in validation fails the compilation: there is no deployment to refuse later, and the
compilation is where the application is put together.

The phases run the same way whichever language the application is compiled in - by javac, by the Groovy
compiler, or by KSP - through two sources the processor generates in that language. One is a marker: a generated
source is compiled after the sources the compilation started with, so the marker coming past is the moment every
class has been registered, and synthesis, the registration of what it described, and validation run then. The
other is a factory with a method for each class an extension named, which is how a bean of a class compiled
elsewhere is declared in all three; the record of a component is put on its method as annotation values when the
factory is compiled. A compilation that cannot compile a generated source fails rather than leaving the phases
out.

Every compilation of an application that has the extension on
its processor path runs it, so a synthetic component is recorded by each; the record carries the extension and
the order it described the component in, and the container registers a component once however many
compilations recorded it.

### The bean manager answers what CDI Lite can

*Section 2.9.1.* The programmatic access CDI Lite describes is the `BeanContainer`. The `BeanManager` of CDI Full
extends it, and is implemented here as far as Lite reaches: looking a bean up, resolving an injectable reference,
comparing two qualifiers or two interceptor bindings by the members that bind, and reading the definition of a
stereotype or an interceptor binding, which is answered by `micronaut-cdi-reflection`. It is a bean, so a program
can inject it.

`BeanContainer.resolveInterceptors` (section 2.9.1.10) resolves the interceptor classes the Jakarta Interceptors
processor compiled: an interceptor is enabled by the priority it declares, bound when every binding it declares
is among the given ones, and the resolved list is ordered lowest priority first. The interception of a bean
still happens where it was compiled; what the manager adds is the description of it the specification asks for,
including invoking an interceptor directly through `Interceptor.intercept`.

What belongs to CDI Full says so rather than answering: decorators, passivation, portable extensions, and
building a bean out of an annotated type. The expression language is the one named exception, provided beyond
Lite by the optional `micronaut-cdi-el` module over `micronaut-jakarta-el`: with it on the classpath,
`getELResolver` answers with a resolver in which a name at the base of an expression is the bean of that name —
a name written as a list of identifiers separated by periods included —
and `wrapExpressionFactory` wraps a factory so that what it creates evaluates with the container's beans in
reach, a dependent bean being created once for an evaluation and destroyed as it completes; without it, both
say the module is missing. The manager is implemented because a program
written against the specification reaches for it — the kit's own tests do — not because CDI Full is claimed.

### A producer compiles wherever it is declared

*Section 2.2.2 / annotated discovery.* Under Lite's annotated discovery a class with no bean defining
annotation is not a bean, and a producer it declares is inert. Here the producer is compiled regardless,
because bean-archive membership is a per-deployment question a global compilation cannot answer — the SE
bootstrap's {@code addBeanClasses} makes exactly such a class a bean by fiat. A producer in a class no
deployment ever admits is the difference visible to code that counts beans.

### Two findings of a reading of ArC's test suite, reported as the class compiles

Both are reported by the compiler rather than at runtime, so neither has a test that runs. A disposer method is
bound to a producer by the rules of typesafe resolution (section 3.3.7), and the qualifiers are compared here for
equality and one occurrence at a time: a producer and a disposer qualified with the same repeatable qualifier
written twice do not match, and every such disposer matches every such producer, so the class is refused with
both "more than one disposer" and "no producer". And an interceptor binding declared on a class reaches the
producer methods of that class, so what a producer of an intercepted class produces is proxied as though it were
intercepted — which a primitive cannot be, and the class is refused.

### Known limitations a review has named

Three findings of an internal review are documented rather than coded around. A dependent bean reached from an
EL expression through the bare resolver of `getELResolver` is created but not destroyed when the evaluation
completes — the EL contract offers the resolver no end-of-evaluation moment to hook; EL is provided beyond
Lite, and a program that needs the destruction can look the bean up and destroy it itself. The expressions of a
factory wrapped with `wrapExpressionFactory` do have that moment, each call of one being an evaluation, and
there the dependent bean is shared by every appearance of its name and destroyed as the call returns. Ending a request begun with
`RequestContextController.activate()` from a different thread than began it silently does nothing — the
controller's bookkeeping is per-thread, as the specification's enter-and-exit shape assumes; the `run`/`supply`
/`call` forms are safe across threads. And a creation that waits for another thread can, in principle, deadlock
when that thread needs to create the same bean — a bean is created under a lock of its own, one for every context
of its scope, which is also what guarantees one instance per context. A creation that waits for another thread
creating a different bean, of the same scope or another, goes ahead (`CrossThreadCreationTest`).

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
(`interceptors/tests/**`): 807 tests, all passing, and part of `check`. A handful of ported assertions and
`ScenarioSweepTckTest` — which reads every scenario bean through one container at once — remain as local
regression tests beside the kit's own.

Beyond Lite, the suite runs a few of the classes the kit marks as CDI Full, in a `beyond-lite` block of their
own: 21 tests, all passing, which makes 828 in all. Each asserts something this implementation answers although
the kit files it under Full — the bean manager's comparison and hash code of qualifiers
(`QualifierEquivalenceTest`), an injectable reference that is unsatisfied or ambiguous
(`UnsatisfiedInjectableReferenceTest`, `AmbiguousInjectableReferenceTest`), interceptors bound with
`@Interceptors` (`MethodLevelInterceptorTest`, `InterceptorBindingsWithAtInterceptorsTest`,
`InterceptorOrderTest`), the definition error of injecting the metadata of a decorator — `Decorator<X>` or
the `@Decorated` `Bean<X>` — into a bean that is not one (the four tests of
`implementation/builtin/metadata/broken/injection`), and the expression language of `micronaut-cdi-el`: names
resolved to beans (`full.lookup.el.ResolutionByNameTest`), a wrapped factory of someone else's
(`WrapExpressionFactoryTest`), and the dependent beans of an evaluation
(`full.context.dependent.DependentContextTest`). One test of the last is left out by name, `testContextIsActiveWhenEvaluatingElExpression`: its method expression
calls a bean method that is not an executable method, and an expression reaches a bean's methods through the
executable metadata compiled for them rather than reflectively. Their scenario packages are compiled by name (`beyondLiteScenarios` in
`cdi-tck/build.gradle`), and the `cdi-full` group stays excluded from every other block. CDI Full as a whole is
still not claimed.

The kit has a second part, `jakarta.enterprise:cdi-tck-lang-model`: 1263 assertions about the language model of
section 2.10 — the `ClassInfo`, `MethodInfo`, `Type` and `AnnotationInfo` a build compatible extension reads a
class through — with one entry point, `LangModelVerifier.verify(ClassInfo)`, which asks the model everything
about the verifier's own class: its members, inherited and declared, its enum constants and annotation members,
bridge methods, repeatable and inherited annotations, the annotations on every use of a type, and the equality
of two readings of one thing. The `micronaut-cdi-tck-lang-model` module resolves its sources the same way,
compiles them with this module's processor and runs the verifier from a build compatible extension as they
compile — the runner the kit ships for the reference implementation, written the same way. The model is read
from Micronaut's AST alone, so that it is the same model in a Java, a Kotlin and a Groovy compilation, and from
the source of the class as it compiles rather than from a class file, which is what loses the type annotations
(JDK-8225377) and why other implementations skip those assertions. The model reports what Micronaut records,
read the way the specification's model reads it — a repeatable annotation Micronaut folded into its container
although it was written once is reported as itself, an annotation interface reports the retention it declares —
and a deployment narrows what an extension sees by registering a `LanguageModelAnnotationFilter`; the kit
module's filter leaves out what Micronaut's mappers write into its own packages and the non-null marker it adds
in null-marked code, since the kit asserts on the source alone. Sixteen of the kit's eighteen sections pass, on Micronaut Core 5.3 (the accessors the model uses — an annotation
interface's targets, container and retention, and the annotations on a primitive type use — landed there); the
two that do not are run as skipped tests that name what each waits on, and both are accepted deviations: one
case of `AnnotatedTypes`, the annotation on one dimension of an array, which Micronaut's model keeps one set of
for the whole array type, and one case of `RepeatableAnnotations`, a repetition written beside a hand-written
container, which Micronaut folds into one container (both under
[Open points in Micronaut Core](#open-points-in-micronaut-core)). Because the model is built on the AST alone, `test-suite-kotlin`
runs a build compatible extension against a Kotlin class as KSP compiles it, and `test-suite-groovy` against a
Groovy class.

## Open points in Micronaut Core

What this module works around in Micronaut Core, or accepts from it, as of Micronaut Core 5.3. None is filed
upstream as a defect; each is a difference of design or a gap with a workaround here.

- **One set of annotations for an array type.** A type annotation written on one dimension of an array
  (`String[] @A [] f`) is not kept per dimension: the element model has one set for the array type, standing for
  the component's. The `AnnotatedTypes` section of the language model kit stops at that assertion and runs as a
  skipped test; accepted, as other implementations skip it.
- **Annotations as the source wrote them are derived, not recorded.** A repeatable annotation is folded into its
  container, and what Micronaut's mappers add is not told apart from what the source wrote. The language model
  unfolds a container of one repetition and filters Micronaut's own annotations
  (`LanguageModelAnnotationFilter`); a repetition written beside a hand-written container is reported as one
  container of all of them, which is where the `RepeatableAnnotations` section stops and runs as a skipped test.
  A remapped annotation's original name is not recovered either.
- **An unannotated use of a type variable reports its declaration's annotations.** For `class C<@X T> { T f; }`
  the type of `f` carries `@X`, where the specification's model has a use report only its own. The kit accepts
  either on the one bound it checks; nothing here is skipped for it.
- **`MethodElement.getReceiverType()` is empty unless the source wrote the receiver.** Its documentation says an
  instance method has one derived from the declaring type; the Java implementation answers only a written `this`
  parameter, and the Kotlin and Groovy ones never do. `ElementMethodInfo.receiverType()` supplies the declaring
  type itself.
- **A field's annotation metadata does not include its declaring class's, a method's does.** The priority that
  selects an alternative producer field is therefore read from the declaring class explicitly
  (`ProducerVisitor.selectIfAlternative`).
- **What a Kotlin compilation cannot answer.** KSP has no package element, so the annotations of a package read
  as none; a Java record seen from KSP is not known to be one; thrown types come only from `@Throws`. The
  language model answers less in a Kotlin compilation in those places.

## What other implementations' tests found

The tests of other implementations are read as a catalogue of cases rather than run here: Weld, the reference
implementation, holds its reading of the type rules of section 2.4.2 to a table of type pairs — raw types,
parameterized types, arrays, wildcards, and type variables bounded by other variables, by several types at once, or
by parameterized types — and ArC, the container of Quarkus, which decides what a bean is while the application is
built as this implementation does while a bean compiles, exercises corners of resolution, interception, producers,
observers and stereotypes the kit passes over. Each case worth having is written again here, against this
implementation and in its own terms: Weld's whole table is `AssignabilityRulesTest`, and ArC's corners are the
tests `InterceptorLifecycleNestingTest`, `StereotypeCompositionTest`, `ObserverOrderingTest`, `ProducerVarietyTest`,
`DependentDestructionTest`, `InjectionPointMetadataTest`, `VetoedBeanTest` and `AbstractBeanTest` cover.

They found what the kit had not. From Weld's table: an array of a parameterized type was not matched at all, and a
type variable's bound was compared by its raw class, so a variable bounded by another variable, or by a
parameterized type, matched too much or too little. From ArC's: a class written with the singleton pseudo-scope
and nothing else got no default qualifier and resolved at no unqualified injection point; a nested class's default
name carried its outer class; a producer of a type with a type variable was refused the dependent scope it is
allowed; a dependent producer in an application scoped class was refused an `InjectionPoint` parameter; and a
programmatic lookup of an abstract class annotated with a scope, whose one bean is a concrete subclass, was
ambiguous, the definition Micronaut compiles for the abstract class being counted as a bean.
