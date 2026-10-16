# Conformance

What this module implements of
[Jakarta Contexts and Dependency Injection 4.1](https://jakarta.ee/specifications/cdi/4.1/jakarta-cdi-spec-4.1.html),
covering Part I.A — CDI Lite. Only CDI Lite is in scope; CDI Full (Part I.B) is not
implemented and is not claimed.

Every difference is recorded here. No test is disabled for one: a test of the kit that a difference rules out is
left out by name, in `cdi-tck/build.gradle` and `tck-suite.xml`, and is named below, and the two sections of the
language model kit that are not passed run as skipped tests that say what each waits on, so that those are as
visible in a test report as what passes.

## Implemented

| Section | What | Where |
| --- | --- | --- |
| 2.4, 6.6 | The dependent pseudo-scope, read as the Micronaut prototype scope | `DependentAnnotationMapper` |
| 2.4, 6.6 | The application scope, read as a proxied Micronaut scope | `ApplicationScope`, `CdiApplicationScope` |
| 2.4, 6.6 | The request scope, and the `ContextNotActiveException` of reaching for it outside a request | `RequestScope`, `CdiRequestScope` |
| 2.3, 3.8 | Qualifiers, and the rule that a bean declaring none has the default qualifier and an injection point declaring none requires it | `DefaultQualifierVisitor`, `CdiBeanContainer` |
| 3.2, 3.3 | Producer methods and producer fields, read as Micronaut factory methods and fields | `ProducerVisitor` |
| 3.4 | Disposer methods, resolved to their producer while it is compiled | `ProducerVisitor`, `DisposerInvoker` |
| 3.5, 3.6, 3.7 | Bean constructors, injected fields and initializer methods, each point resolved by the container | Micronaut's own injection, `CdiBeanInjectionProvider` |
| 2.2 | Narrowing the bean types of a bean with `@Typed` | `TypedAnnotationMapper` |
| 8 | Interceptor bindings | deferred to Micronaut Jakarta Interceptors |
| 5.6 | Programmatic lookup through `Instance`, including handles | `CdiInstance` |
| 11.1 | The `BeanContainer`, and the `Bean` a lookup resolves to | `CdiBeanContainer`, `CdiBean` |
| 11.1 | The `BeanManager` of CDI Full, as far as CDI Lite can answer it, and injectable as a bean | `CdiBeanContainer` |
| 11.1 | `CDI.current()`, found through the service loader | `MicronautCDIProvider`, `MicronautCDI` |
| 6.2 | The `Context` of each scope, and whether it is active | `CdiContext` |
| 5.2 | The rules of resolution applied to types and qualifiers on their own | `CdiAssignability` |
| 11.1.9 | Interceptor resolution through the bean container | `CdiBeanContainer` |
| 9.2 | Firing an event, synchronously and asynchronously, and narrowing one with `select` | `CdiEvent` |
| 9.3 | Observer resolution by the event's type and qualifiers | `ObserverRegistry`, `CdiAssignability` |
| 9.4 | Observer methods, including static ones, `Reception.IF_EXISTS` and `@Priority` | `ObserverVisitor`, `CdiObserverMethod` |
| 9.5 | Observer notification, in ascending order of priority | `ObserverRegistry` |
| 9.6 | The container lifecycle events: `Startup`, `Shutdown`, `@Initialized`, `@BeforeDestroyed`, `@Destroyed` | `ContainerLifecycle` |
| 13.5 | `@Vetoed`, on a class and on a package | `BeanDiscoveryVisitor` |
| 2.7 | Alternatives, selected by `@Priority`, replacing the beans they are an alternative to | `BeanDiscoveryVisitor` |
| 2.8 | Stereotypes, which carry the scope, qualifiers, name and alternative status they declare | Micronaut's meta-annotations |
| 2.2 | The bean types of a bean, including a produced array, interface and primitive, and narrowing with `@Typed` | `CdiBean` |
| 2.2 | A primitive and the class that boxes it as one bean type | `CdiTypes`, `CdiInstance`, `CdiBeanContainer` |
| 3.5 | A managed bean has a constructor taking no parameters or one annotated `@Inject` | `BeanDiscoveryVisitor` |
| 5.2 | Typesafe resolution of an injection point, an `Instance` and the bean manager through one selection of candidates, by every qualifier named, with `@Nonbinding` members left out of the comparison, on the values each qualifier was compiled with and the qualifiers the container records of each bean | `CdiBeanContainer`, `CdiBeanInjectionProvider`, `ContainerInjectionVisitor`, `CdiQualifier`, `NonbindingMembersRemapper`, `CompiledAnnotationTypes` |
| 5.6.1, 9 | `Instance` and `Event` selected by `AnnotationValue` and `Argument`, and `Instance` and `BeanContainer.getBeans` narrowed by a Micronaut `Qualifier`, beside the specification's selections | `MicronautInstance`, `MicronautEvent`, `MicronautBeanContainer` |
| 12.3 | The `@Enhancement` phase of a build compatible extension, and the language model it reads | `BuildCompatibleExtensionVisitor`, `io.micronaut.cdi.processor.extension` |
| 12.2 | The `@Discovery` phase, registering an annotation as a qualifier, an interceptor binding or a stereotype | `DiscoveredClasses` |
| 12.5 | The `@Synthesis` phase, run once the classes of a compilation have been registered, and the synthetic beans and observers it describes, each registered as a bean definition of the class the extension named, without a source | `BuildCompatibleExtensionVisitor`, `SynthesisPhase` |
| 12.6 | The `@Validation` phase, whose errors fail the compilation | `BuildCompatibleExtensionVisitor` |
| 12.4 | The `@Registration` phase, run over each bean and observer as it is compiled, and over the synthetic beans and observers and the built-in beans once synthesis has run | `ElementBeanInfo`, `ElementObserverInfo`, `SyntheticBeanInfo`, `SyntheticObserverInfo`, `BuildCompatibleExtensionVisitor` |
| 12 | `AnnotationBuilder` and `Types`, composing annotation values and types of the one language model | `ElementAnnotationBuilder`, `ElementBuildServices`, `VisitorTypes` |
| 12.5 | Synthetic beans and synthetic observers at runtime, registered from what was recorded, with the creation and disposal functions, the parameters and the lookup they are handed | `RecordedSynthesis`, `CdiParameters`, `SyntheticObserverMethod` |
| 12.1 | `ScannedClasses.add` and `MetaAnnotations.addContext`, applied while the classes are compiled | `DiscoveredClasses`, `ExtensionContexts` |
| 25 (SE) | The SE bootstrap: `SeContainerInitializer` through the service loader, `SeContainer` over a Micronaut context, discovery turned off as a narrowed one | `MicronautSeContainerInitializer`, `MicronautSeContainer` |
| 25.1 | An alternative no priority selected, enabled by `selectAlternatives`/`selectAlternativeStereotypes` as the container is built | `CdiSelectableAlternative`, `UnselectedAlternative` |
| 6.5.2 | `@ActivateRequestContext` as the built-in Jakarta interceptor at `PLATFORM_BEFORE + 100`, so the application's interceptors stand on either side of it | `ActivateRequestContextJakartaInterceptor` |
| 6.6 | The request context active during any bean's `@PostConstruct` and during asynchronous observer notification, and its `@Initialized`/`@BeforeDestroyed`/`@Destroyed` events | `RequestScope`, `ObserverRegistry` |
| 10 | Method invokers: `InvokerFactory` in the registration phase, validated as the bean compiles, invoking the compiled executable method at runtime with the instance and argument lookups of the specification | `ElementInvokerFactory`, `RecordedInvoker` |

## Not yet implemented

The transaction phases an observer may name (9.4) have no transactions to observe here and are notified as
if `IN_PROGRESS`.

## Differences

### A lookup of Micronaut's resolves to the default bean through the resolution customizer

Section 3.8 has an injection point that declares no qualifier looking for the default qualifier, and section
2.3 has a bean that declares no qualifier having it. Both halves are applied by the container. The processor
writes `@Default` onto a bean that declares no qualifier (`DefaultQualifierVisitor`), and every injection point of
a bean of the specification is resolved by the container rather than by Micronaut
([Container injection](#container-injection-resolves-the-full-declared-bean-type)): among the beans of its type,
the ones that have every qualifier the point requires, `@Default` where it names none. An `Instance`, the bean
manager and the deployment validation select their beans the same way, so none of them can resolve a type
differently from an injection point.

The injection points of a bean of Micronaut's own are not resolved by the container, and neither is a program's
own `BeanContext.getBean`. Where several beans are candidates for one and no primary bean decides, Micronaut asks
the container's resolution customizer (`CdiResolutionCustomizer.resolveNonUniqueBean`), which picks among the
beans of the specification as the specification does: one that names no qualifier resolves to the bean with the
default qualifier rather than being ambiguous, and a selected alternative of the highest priority outranks the
rest. No bean is declared Micronaut's `@Primary` for it, so an application's own primary bean decides as it does
without this module, a class written with the singleton pseudo-scope alone among them. The difference left is
there: at a point of a bean of Micronaut's own, or in a `BeanContext.getBean`, that names no
qualifier where every candidate is qualified, the specification has no bean to resolve, and Micronaut resolves
one.

A class written with the singleton pseudo-scope alone, `jakarta.inject.Singleton`, is given the default qualifier
and its name as a bean of the specification is (`SpecificationBeans.isBean`), since the specification names the
pseudo-scope a scope of its own and Micronaut writes the same annotation; the rules a bean of the specification is
held to as it compiles, and the container's resolution of its injection points, are not applied to it, since
Micronaut's own beans carry the same annotation.

### An unproxyable normal scoped bean is detected as it is compiled

*Section 3.10.* A bean in a normal scope has to be proxyable, and the specification has the container detect a
bean that is not: a final class, a class with a final method, a primitive, an array. A sealed class is detected
too: a proxy is a subclass it does not permit. This module detects them as
the class compiles. An intercepted bean that cannot be proxied is refused by the compiler. A bean in a normal
scope deploys, as the specification has it, and what is wrong with it is recorded: an injection point that
resolves to it is a `DeploymentException` the container reports as it starts, whichever way it was started
(CDI 4.1 section 5.4), and a contextual reference asked for at runtime is an `UnproxyableResolutionException`.
The kit's deployments that exist to be rejected are excluded from the scenarios compiled here for that reason,
listed by name in `cdi-tck/build.gradle`.

### What takes the whole deployment is validated as the container starts

*CDI 4.1 sections 5.2.2, 5.3.1, 5.4 and 13.2.* What can be judged while one class compiles is reported by the
compiler. Which bean an injection point resolves to, and which beans share a name, takes the whole deployment -
a bean may be compiled in another module, or be registered as the container starts - so the container validates
its beans as it starts, from what they were compiled with, through the SE bootstrap and an `ApplicationContext`
alike, and with portable extensions before `AfterDeploymentValidation`. An injection point of a bean's fields,
constructor, initializer methods, and observer and disposer methods that resolves to no bean, to more than one
that cannot be told apart, or to a bean in a normal scope that cannot be proxied; two beans of one name; and a
name that is the path prefix of another - each is a `DeploymentException`, every one found being reported. A bean another
one replaces with Micronaut's `Replaces` is no bean of the deployment: its dependencies and its name are not
validated, and neither a lookup by its types nor one by its name finds it.

Only the beans of the specification are validated; a bean of Micronaut's own that shares the context is resolved
by Micronaut's rules. A point is validated by the selection it is resolved by as the bean is created
(`CdiBeanContainer.selectionFor`), and one that selection leaves to Micronaut's rules is validated by them. An
injection point is validated whatever package its type is in: what the container hands
the constructor of the subclass Micronaut generates for an intercepted bean is told by what it is, and the bean
manager, a built-in bean, is handed the context it belongs to. A disposer's parameters are validated as the
overload its producer recorded, which is the one invoked. Collections, arrays, streams, maps and `Optional`
injection require a bean of the full declared type. Explicitly nullable injection may be unsatisfied. An
injection point of `Instance`, `Provider`, `Event` or `InjectionPoint`, or of their Micronaut forms, resolves
late by design and is not validated, and neither is one Micronaut resolves itself - the context, a value of the
configuration - which the processor leaves unmarked.

A deployment is the beans its container sees, so a program that keeps beans for different deployments on one
classpath narrows each container to its own: the SE bootstrap's synthetic archive, a beans predicate, or a
`@Requires` the deployment meets.

### A field is injected only where it is annotated Inject

*CDI 4.1 section 3.6.* Micronaut injects a field that declares a qualifier even without `@Inject`. A qualifier on
a field of a bean that is neither injected nor a producer field qualifies nothing in the specification, whether
it was written there or a build compatible extension added it, so it is taken off as the bean compiles and the
field is left as it was initialized.
Under KSP the exception is a Kotlin property that declares a qualifier and no `Inject`: Micronaut's Kotlin metadata
reads the qualifier of a property as `Inject` on the property and its setter, so the processor cannot tell it from
one written with `Inject`, and the property is injected.

### A private producer or observer is read reflectively

*Sections 3.2, 3.3 and 9.4.* The specification allows a producer method, a producer field and an observer
method to be private, and a private member cannot be read from the bean definition Micronaut generates beside it.
Such a member is annotated `@ReflectiveAccess`, which is Micronaut's way of saying that it is read reflectively,
and only that member is: everything else about the bean goes on being resolved the way it was compiled. What the
author wrote therefore decides where reflection is used, rather than the module deciding it for them.

### A qualifier is compared as the values it was compiled with

*Sections 3.3, 5.2 and 9.3.* The specification's interfaces take and report a qualifier as an annotation
instance. The container compares one as the values it was written with: two qualifiers are the same when they
are of one type and their binding members are equal, a member left at its default being equal to the default
written down. Nothing is materialized to resolve a bean, inject an `Event` or an `Instance`, or notify an
observer. An instance is made only when a program asks for one - `Bean.getQualifiers()` and its like - and that
is the reflection module's, which synthesises it with Micronaut's `ReflectionAnnotations` and reads the members
of one a program hands over the same way.

The members that do not bind are recorded once, the way Micronaut records its own `@NonBinding` members: listed
under `$nonBinding` in the values of the qualifier. Since Micronaut Core 5.3 Micronaut lists every member the
annotation interface marks on every occurrence it compiles, one that leaves the member at its default among them,
and on its qualifier stereotype. The processor adds what Micronaut cannot know (`NonbindingMembersRemapper`): a
member a build compatible extension marked `@Nonbinding` while the compilation discovered its classes, on every
occurrence of the qualifier or binding; and the members a qualifier the processor records for a synthetic
component excludes, which an extension composed rather than the compiler reading it. A qualifier the program
composes as an `AnnotationValue` lists nothing, and one read from an annotation instance lists what its type
marks, so a member is left out of a comparison when either side lists it. An injection point, a programmatic lookup
and the bean manager select their beans by these and nothing else (`CdiBeanContainer.beansOf`). Micronaut's own qualifier for an
annotation instance or value (`Qualifiers.byAnnotation(Annotation)`, `byAnnotation(AnnotationMetadata,
AnnotationValue)`) no longer falls back to the name of the bean class since Micronaut Core 5.3, but it compares by
the qualifiers a bean was compiled with alone, while the qualifiers of a bean of the
specification are also the ones a portable extension added as the container started, a bean that declares
none has `@Default` and `@Any`, and two values are compared by the members that bind as `BindingValues.matches`
compares them (an enum constant by its name, a class as its `AnnotationClassValue`); so the container compares
the qualifiers itself.

A qualifier and an interceptor binding are compared by one rule, the Jakarta Interceptors implementation's
`BindingValues.matches`, so that `areQualifiersEquivalent` and `areInterceptorBindingsEquivalent` answer as the
interceptor chains are built: the members a value leaves out filled in from the defaults it carries, the members
either side lists under `$nonBinding` left out of both, an empty string compared as the member left out, only
`$nonBinding` and `$stereotypes` of Micronaut's members left out, an enum constant by its name, a class as its
`AnnotationClassValue`, an array element by element, and an annotation held by a member compared as a binding
is - by the members it binds by rather than by every member, which section 5.2.6 leaves non-portable for a
qualifier member that is an annotation and is not `@Nonbinding`. Two things the container does beside it:

- **A value that carries no defaults is given the ones Micronaut registered for its type** before it is
  compared. Compiled metadata hands every value its defaults; a qualifier a program composes, as
  `AnnotationValue.builder(Shelf.class).build()`, carries none, and `BindingValues`, which fills defaults from the
  value alone, would not match it with the bean compiled with `@Shelf`. An annotation such a value holds is
  compared with the defaults it carries, as `BindingValues` compares it. The value with the defaults is also what
  the container hands the interceptors implementation (`BeanManager.resolveInterceptors`), so the two still agree.
- **`getQualifierHashCode` and `getInterceptorBindingHashCode` hash the members that bind.**
  `BindingValues.hashCode` hashes the name alone, because the two values it compares may list different members
  as non-binding; the container hashes an annotation instance, whose non-binding members are those of its type
  whichever instance it is, and the specification's tests ask two qualifiers that differ by a binding member to
  hash differently. The members are hashed as `BindingValues.normalize` shapes them, an annotation a member holds by
  its name, so two annotations that match hash alike.

An annotation literal a program hands to `select`, `getBeans` and their like is a reflection object. The
literals of the specification and a literal of a qualifier with no binding member are taken as they are; any
other has to be read member by member, which needs `micronaut-cdi-reflection` and says so. The reflection-free
form of the same selection is an `AnnotationValue`, through `MicronautInstance`, `MicronautEvent` and
`MicronautBeanContainer`.

What the container has to know of a qualifier or interceptor binding type - whether it is one, its members and
which of them bind, whether it is repeatable, its retention - is read from the compiled metadata of the beans
(`CompiledAnnotationTypes`): the class level metadata of every definition, the records of the synthetic components
and of the qualifiers an extension registered, and, for a type no bean class carries, the injection points and
executable methods of the definitions. An annotation `BeforeBeanDiscovery.addQualifier` makes a qualifier is one
for that container. A type the application was not compiled with has no record, and is asked of its class by the
reflection module.

### Bean types, observed types and event types are what the compiler recorded

*Sections 3.1, 5.2.1 and 9.1.* The type closure of a bean, the type an observer observes and the closure
an event is matched by are properties of generic signatures, which the container does not read. The processor
records each: the closure of a bean on the bean or on its producer - the class over its own type variables first,
which is what the container also reads the generic hierarchy of the class of a bean from; the observed type on the
observer; and the closure and the type variables of every other class a compilation compiles that has a
parameterized type above it or declares type variables - the classes no definition describes, less an interface,
an abstract class and an annotation literal, which are never the class of an event object - with the closure of
every class a recorded type names as a type argument or a bound, a class of the JDK or of a library among them,
which is what a bound is compared against, on definitions of a record it registers with Micronaut's
`VisitorContext.registerBean` as each round finishes, with no source generated. What refers to public classes
alone is recorded once for the compilation, registered from a class of it; the record of a class that refers to a
class only its own package can name is registered from a class of that package, which places its definition
there; a class of a library that refers to such a class is not recorded, since nothing the compilation writes is
placed in a package of the library. Inside the container each of these types is a Micronaut `Argument`; a `java.lang.reflect.Type` is
read into one where a program hands it to the specification's API and made from one where that API reports it,
in `io.micronaut.cdi.internal.type`, and not by Micronaut's `ReflectionArguments`, whose exact readings are in a
module that reads classes (see [Open points in Micronaut Core](#open-points-in-micronaut-core)).
The type of an event is the class of the event object with its type variables resolved from the
type the event was fired as, worked out from that record, through the type arguments and the components of the arrays the
supertypes of the class use them in - `class Batch<T> implements Payload<T[]>` fired as `Payload<String[]>` is a
`Batch<String>` - and an object whose class leaves a variable
unresolved is refused from it.

A class no compilation with this processor has seen has no record. With `micronaut-cdi-reflection` its generic
signature is read, as the specification assumes. Without it a bean of such a class has its class and the raw
types Micronaut exposes it as for bean types, and an event of such a class is of the type it was fired as where
that names its class and of its raw class otherwise. Its closure is the closure of its raw class and that of the
type it was fired as, which `Event<T>.fire(T)` makes a supertype of the class: an `ArrayList` fired through an
`Event<List<Duck>>` is observed as a `List<Duck>`. It is observed by an observer of a raw type, of the type it was
fired as or of a type above that, and not by an observer of a parameterized supertype between its class and the
type it was fired as. `MicronautEvent.select(Argument)`
states the event type in full and needs no record and no module.

### The API written in terms of reflection is an optional module

*Sections 5.5.7, 5.5.8, 5.6, 9.4.3, 11.1.4 and 11.1.8 to 11.1.10, and of CDI Full 23.1 and 23.3.10.*
`micronaut-cdi` reads no class back, and the build holds it to that: the `NoReflection` check allows it nothing
but the accessors of a `java.lang.reflect.Type` it was handed and the making of the value classes of
`io.micronaut.cdi.internal.type`, which such a type is reported as. The
methods of the specification that return a reflection object, or that can only be answered from one, are
answered by `micronaut-cdi-reflection`, and without it each throws an `UnsupportedOperationException` naming the
module: `InjectionPoint.getMember()` and `getAnnotated()`; the qualifiers and interceptor
bindings of a bean, an injection point, an observer or an event reported as annotation instances, for an
annotation that is not one of the specification's and whose type the application did not list in
`@RegisterAnnotations` (Micronaut Core generates a builder for a type listed there, which makes the instance
without reflection); an annotation literal with binding members handed to a lookup, unless it carries its
values as an instance such a builder made does; `isScope`, `isNormalScope`, `isQualifier`, `isStereotype` and
`isInterceptorBinding` for an annotation the build recorded nothing of; `getStereotypeDefinition` and
`getInterceptorBindingDefinition`; and the generic hierarchy of a class no compilation with this processor has
seen. Everything else - resolution, injection, events, interception, the contexts, what an extension registered -
needs only `micronaut-cdi`, and
`MicronautInstance`, `MicronautEvent` and `MicronautBeanContainer` give the selections a reflection-free form.

### The application context is destroyed as the context begins to stop

*Sections 6.6.2 and 9.6.* As a Micronaut context begins to stop, before it destroys any bean, `Shutdown` is
fired, then `@BeforeDestroyed(ApplicationScoped.class)`; the application context is then destroyed - every
application scoped bean, and the dependent objects of each - and `@Destroyed(ApplicationScoped.class)` is fired.
Two things follow from the order. A bean of the singleton pseudo-scope belongs to no context and is destroyed by
Micronaut afterwards, so after `@Destroyed(ApplicationScoped.class)`. And an application scoped bean that
observes `@Destroyed(ApplicationScoped.class)`, or is reached by a singleton as it is destroyed, is a new
instance created for that, and is destroyed when the context has stopped. What an observer of these events
throws is logged and stops neither the events after it nor the context from stopping.

### Container injection resolves the full declared bean type

*Section 5.2.* `List<Foo>` and `Optional<Foo>` are bean types like any other. Every injection point of a bean of
the specification - a field, a parameter of its constructor, of an initializer or of a producer, and the
parameters of an observer and of a disposer method - is resolved by the container: `ContainerInjectionVisitor`
selects Core's `@ResolveWith` provider hook for it as the bean compiles, and `CdiBeanInjectionProvider` resolves
the full declared type with the qualifiers the container records of each bean. What Micronaut resolves itself is
left to it and not marked: a lookup (`Instance`, `Provider`, `BeanProvider`), an `Event`, the injection point
metadata, the context under the interfaces it hands itself out as, Micronaut's `Qualifier`, which it injects as the
qualifier the bean is resolved by, a value of the configuration. A point the
application marked with a `@ResolveWith` provider of its own is left to that provider and not validated: a field or
a parameter of a bean's own is supplied by Micronaut from it, and a parameter of an observer or a disposer method
by the container from it, through a resolution of its own that starts at the bean declaring the method - what the
provider creates there is not a dependent of the invocation (`CdiResolution.fromApplicationProvider`).

A point is marked by the class that declares it. Micronaut keeps the annotations of a field and of a parameter by
the class declaring the member, so a mark written while a subclass is compiled would be read by every class that
inherits the member - a bean of Micronaut's own that extends the same class would have its `List<Foo>` resolved as
a bean of that type rather than collected. A superclass that is itself a bean of the specification marks its own
points; the points a bean of the specification inherits from a class that is not one - an abstract class, a class
with no scope, a superclass compiled without this processor - are resolved by Micronaut's rules, with the type rules
of section 2.4.2.1 (`CdiResolutionCustomizer.isCandidateBean`), as a bean of Micronaut's own resolves them.

There is one selection of candidates, `CdiBeanContainer.beansOf`, which the provider, an `Instance`, the bean
manager's `getBeans` and `resolve`, the observer and disposer parameters and the deployment validation all make:
the beans one of whose recorded bean types matches by the rules of section 2.4.2.1 - a type Micronaut only indexes
a definition under is none of them - with every required qualifier, a qualifier a portable extension added among
the ones a bean has; less a bean another one replaces with Micronaut's `Replaces`, an interceptor, an abstract
class and the target a proxy stands in front of. Resolving one bean narrows them as section 2.4.2 has it: a
bean of the specification outranks a bean of Micronaut's own of the same type, and the alternatives outrank
everything else (`CdiResolution.narrowToOne`). The beans are read from the definitions once per container, their
types and qualifiers once per bean, and the beans of a class once per class asked for. What an injection point resolves
to is worked out once per point and container. Core's active resolution
context creates the selected definition and retains its dependent ownership and the path the injection point
metadata is read from. Missing required producers are deployment errors.

A bean of the specification may be injected with a bean of Micronaut's own: where no bean of the specification
is of the class of the point, or none of them qualifies while beans of Micronaut's own are of it, the point is
resolved by Micronaut's rules - its qualifier, its primary bean, the one that is not secondary, its name
convention - among the beans of Micronaut's own alone (`CdiBeanContainer.selectionFor`), and validated by them. The point keeps
its full declared type: a `List<Foo>` is resolved as a bean of that type, as the specification has it, rather than
collected as Micronaut collects one at a point of a bean of its own. A
bean of the specification the qualifiers of the point rejected is not admitted again by Micronaut's rules: an
`@Inject @Fancy Engine` beside a bean of the specification of `Engine` that is not `@Fancy` and a bean of
Micronaut's own is not injected with the first by the prefix of its name, and an `@Inject Shape` beside a `@Fancy`
bean of the specification and a bean of Micronaut's own is injected with the bean of Micronaut's own, as an
`Instance` and the bean manager resolve it. A lookup that resolves one bean among beans of
Micronaut's own alone - `Instance.get()`, the bean manager's `resolve` - chooses by Micronaut's precedence as
well: the primary ones where there are any, the choice of the resolution customizer, the ones that are not
secondary, the lowest order, the default implementation and the exact type (`CdiResolution.amongMicronautsOwn`). A bean of Micronaut's
own keeps Micronaut's resolution and aggregation for its own injection points, with the type rules of section
2.4.2.1 for a parameterized type a bean of the specification has (`CdiResolutionCustomizer.isCandidateBean`).

### A primitive is boxed by the lookup rather than by the bean

*Section 2.2.* A primitive type and the class that boxes it are one bean type. Micronaut resolves a bean by the
type it was written as and keeps the two apart; the container compares a primitive and the class that boxes it as
one type wherever it selects beans - an injection point of a bean of the specification, a lookup, the bean
manager. An injection point of a plain Micronaut bean is not rewritten, so it goes on
resolving the way it did: a field of `Double` injected into one does not resolve a producer of `double`.

A dependent producer that returns null satisfies a primitive injection point with the default value of the
primitive (section 2.5.2.4) - a field, a parameter of a bean's own, and a parameter of an observer or a disposer
method alike. The null product a callback parameter of a primitive type is resolved to is created on its own
rather than as a dependent of the invocation and destroyed with it where it is not null: Micronaut destroys a
dependent through its pre-destroy listeners, which may not answer a null bean (see
[Open points in Micronaut Core](#open-points-in-micronaut-core)).

### Every phase of an extension runs while the application compiles

*Section 12.* Discovery runs as the compilation starts. Enhancement and registration run as each class is
compiled. Synthesis, the registration of what it described, and validation run once every class of the
compilation has been registered. An extension therefore goes on the annotation processor path of the project it
is written for rather than on its classpath, and is still found through the service loader as the specification
says. The running application does not load it, and an extension that is only on the runtime classpath does
nothing.

What the discovery phase says is recorded by name and applied as the named classes come past the compiler.
A class `ScannedClasses.add` names is a bean even where it says nothing at all about itself. KSP and the Groovy
compiler hand every class they compile to the bean machinery, which makes a scanned one a bean with the scope it
was given as it was visited; a scanned class of a library, which no compilation of the application compiles, is
put through the discovery, enhancement and registration phases and the visitors of the processor that read a class
- its scope, its injection points marked for the container, its bean types, its name, its default qualifier, its
producers and observers - and registered as a bean with Micronaut's `VisitorContext.registerBean`, its producers
with `produceBeans`: what the visitors recorded of a producer is given to the bean it produces, which the builder
reads anew. The rules a class is held to as it compiles are not checked of it: they are its own compilation's to
judge, and KSP reads less of a class from its bytes than of a source (a public field of a Java class reads as final). javac hands the bean machinery only the classes that
carry an annotation it reacts to, and decides which of those are beans after the visitors have run, so a bean
registered for a scanned class could stand beside the definition javac writes for it: under javac the processor
generates a `@ClassImport` source naming the scanned classes, the one source left of the extensions, and javac makes each of them a bean once. `MetaAnnotations.addContext` registers a bean of the
context class, recording the scope it serves, and the runtime obtains the context from that definition. The
annotations registered as qualifiers are recorded on a bean registered for the purpose, of the type
`CdiDeploymentRecord`, which is never created. An annotation registered as a qualifier, an interceptor binding or
a stereotype is one only if it is compiled by the same build.

A synthetic bean is registered as a bean of the creator class the extension named, without a source, and the
rest of what the extension said - the bean types, the qualifiers, the scope, the name, the priority, the
parameters - is the annotation metadata of that definition. The disposer class and the observer class of a
synthetic observer get a definition the same way. As the container starts it reads those records, registers a
bean for each synthetic bean, and obtains a creator, a disposer, an observer or a context from its definition:
it loads no extension, invokes nothing reflectively and instantiates no class by name. The parameters an
extension attaches with `withParam` are the types the specification allows, all of which an annotation value
holds. The classes the extension names have to be on the classpath the application is compiled against, and
each needs a constructor without parameters the generated definition can call.

The language model an extension reads, and the annotations and types it composes with `AnnotationBuilder` and
`Types`, have one implementation, built on the compiler's view of the classes. A synthetic bean, a synthetic
observer and the built-in beans are described to the registration phase in that model too. A bean is described to
the registration phase as the container resolves it: a class bean only where the class can be one - not vetoed,
not abstract, with a bean constructor - a producer by the class that declares it, its bean types narrowed by
`Typed` and ending in `Object`, its qualifiers with `Any` and without the ones Micronaut writes of its own accord,
an alternative stereotype making it an alternative, and an observer with the priority it is notified in, written
on its parameter or on the method.

An error an extension reports through `Messages`, or a problem it throws, in synthesis, in the registration of a
synthetic component or in validation fails the compilation: there is no deployment to refuse later, and the
compilation is where the application is put together.

The phases run the same way whichever language the application is compiled in - by javac, by the Groovy
compiler, or by KSP - with no source generated for them. Micronaut calls `TypeElementVisitor.finishRound` once
every visitor has visited every class of a round, the registration visitor included, and before the beans of the
round are written, in all three; the first round that visits a class of the compilation is the moment every class
has been registered, and synthesis, the registration of what it described, and validation run then, once. Under
javac, where the scanned classes are imported, they wait for the next round, which visits the imported classes.
Each class an extension named - a creator, a disposer, a synthetic observer, a context - is declared a bean with
`VisitorContext.registerBean`, which is how a bean of a class compiled elsewhere is declared in all three, and
the record of the component is put on the definition as annotation values. The definition is named after the
first class of the compilation and has every class of it as an origin: under javac, Gradle then recompiles the
whole source set when one changes, and under KSP the definitions are written again with any of them. A Groovy or
Kotlin compilation that finished no round fails rather than leaving the phases out.

Every compilation of an application that has the extension on
its processor path runs it, so a synthetic component is recorded by each; the record carries the extension and
the order it described the component in, and the container registers a component once however many
compilations recorded it.

### A portable extension is run over what was compiled

*Sections 23, 24.2 and 25.1.* A portable extension - a class that implements `Extension` and observes the
container lifecycle events - belongs to CDI Full; the extensions of CDI Lite are the build compatible ones of
section 12, which run while the application compiles. `SeContainerInitializer.addExtensions`, and the
service loader of `jakarta.enterprise.inject.spi.Extension` through the class loader the initializer was given,
are nevertheless answered for the SE bootstrap, by the optional `micronaut-cdi-reflection` module: a subset of
the lifecycle that a container of beans compiled ahead of time can answer. It is offered beyond Lite; CDI Full
is not claimed. Without the module a bootstrap that was handed an extension fails with an
`UnsupportedOperationException` that names it, and the service loader is not consulted. A context started as a
Micronaut `ApplicationContext` rather than through the SE bootstrap runs no portable extension.

The beans exist before the container starts, so the events describe them rather than discover them. They are
fired before `@Initialized(ApplicationScoped.class)` and `Startup`, in the order of section 24.2:
`BeforeBeanDiscovery`; `ProcessAnnotatedType` for each bean class of the application; `AfterTypeDiscovery`; for
each bean `ProcessInjectionTarget` (a managed bean), `ProcessBeanAttributes` and `ProcessBean` - a
`ProcessManagedBean` for a managed bean; `AfterBeanDiscovery`; and `AfterDeploymentValidation`. The observers of
an event are notified in the order of the `@Priority` of their event parameter, and an observer may take the
`BeanManager` beside the event. An extension is a bean: one instance, with the default qualifier.

What an extension can do:

- `BeforeBeanDiscovery.addQualifier(Class)` makes an annotation a qualifier for that container;
- `ProcessAnnotatedType.configureAnnotatedType().add(qualifier)` adds a qualifier to a bean class. It is seen
  where this container compares qualifiers - an injection point of a bean of the specification, `Instance`, the
  SE container and `BeanContainer` lookups, `Bean.getQualifiers()` - and takes the default qualifier away as a
  written one does. It is not seen by an injection point of a bean of Micronaut's own, which Micronaut resolves
  from the compiled metadata of the definitions;
- `AfterTypeDiscovery.getAlternatives()` lists the classes of the selected alternatives, and cannot change them;
- `AfterBeanDiscovery.addContext` adds a context, which for `@RequestScoped` or `@ApplicationScoped` takes the
  place of the container's own; `addObserverMethod(ObserverMethod)` adds an observer method; and
  `getAnnotatedType`/`getAnnotatedTypes` describe a class;
- `addDefinitionError` on any event that has it, and an exception an observer throws, fail the bootstrap with a
  `DefinitionException`; `AfterDeploymentValidation.addDeploymentProblem` fails it with a `DeploymentException`.

What would change a bean that was compiled is refused with an `UnsupportedOperationException` that says so,
rather than ignored: `BeforeBeanDiscovery.addQualifier(AnnotatedType)`, `addScope`, `addStereotype`,
`addInterceptorBinding`, `addAnnotatedType`, `configureQualifier` and `configureInterceptorBinding`;
`ProcessAnnotatedType.veto` and `setAnnotatedType`; on the type configurator, `add` of an annotation that is not
a qualifier, `remove`, `methods`, `fields` and `constructors`; `AfterTypeDiscovery.getInterceptors`,
`getDecorators` and `addAnnotatedType`; `ProcessInjectionTarget.getInjectionTarget` and `setInjectionTarget`;
`ProcessBeanAttributes.veto`, `setBeanAttributes`, `configureBeanAttributes` and `ignoreFinalMethods`;
`ProcessManagedBean.createInvoker`; `AfterBeanDiscovery.addBean` and the configurator form of
`addObserverMethod`. A build compatible extension does each of those while the application compiles.

Not fired: `ProcessInjectionPoint`, `ProcessProducer`, `ProcessObserverMethod`, `ProcessProducerMethod`,
`ProcessProducerField`, `ProcessSyntheticBean`, `ProcessSyntheticAnnotatedType` and `BeforeShutdown`; a produced
bean is described by `ProcessBeanAttributes` and a plain `ProcessBean`, and `getAnnotated()` of a producer
method is not described. An extension with an observer of any other event - an event of the application among
them - is refused as the bootstrap starts, rather than left waiting for it. The beans Micronaut and this
container bring of their own, which are the ones under `io.micronaut` and `jakarta`, are not described.

### The bean manager answers what CDI Lite can

*Section 11.1.* The programmatic access CDI Lite describes is the `BeanContainer`. The `BeanManager` of CDI Full
extends it, and is implemented here as far as Lite reaches: looking a bean up, resolving an injectable reference,
comparing two qualifiers or two interceptor bindings by the members that bind, and reading the definition of a
stereotype or an interceptor binding, which is answered by `micronaut-cdi-reflection`. It is a bean, so a program
can inject it.

`BeanContainer.resolveInterceptors` (section 11.1.9) resolves the interceptor classes the Jakarta Interceptors
processor compiled: an interceptor is enabled by the priority it declares, bound when every binding it declares
is among the given ones, and the resolved list is ordered lowest priority first. The selection is the one Jakarta Interceptors makes for a
chain, asked of it (`InterceptorBindings.boundInterceptors`): its own comparison of the bindings its processor
recorded for the interceptor class with the values of the ones given, the enablement and the order of the chain
included, so what the manager reports bound is what runs; the manager keeps the ones that interpose on the kind of
interception asked about. The interception of a bean still happens where it was compiled; what the manager adds
is the description of it the specification asks for, including invoking an interceptor directly through
`Interceptor.intercept`.

The interceptors that run are the ones the manager reports. Section 8 has CDI Lite enable and order
interceptors with `@Priority`, the way section 5.2.1 of Jakarta Interceptors enables an interceptor bound by an
interceptor binding. The Jakarta Interceptors implementation on its own enables every interceptor class, with or
without a priority; the container provides the enablement of CDI to it (`CdiInterceptorEnablement`), so an
interceptor bound by an interceptor binding that declares no priority is left out of the chains as well as out of
`resolveInterceptors`. The SE bootstrap's `SeContainerInitializer.enableInterceptors` (section 25.1) adds the
interceptor classes it names to the enabled ones without a priority. An interceptor class a bean names with
`@Interceptors` is enabled by that annotation (Jakarta Interceptors section 5.1); section 19 requires that form of
association of CDI Full only, and it works here all the same.

`Bean.create()` creates a new contextual instance each time it is called (section 6.1); the instance a scope
holds is what its context hands out and what a contextual reference resolves to. Normal-scoped and singleton
instances are created with Core's `BeanContext.createBeanRegistration`, bypassing their scope while retaining
the complete dependent tree. Their creational context closes that registration, so dependent objects are
released exactly once and the shared scoped instance remains alive.

Disposer invocations acquire their temporary dependencies through `BeanPreDestroyEvent.withDependencies`.
Core permits these lookups during the destruction callback, including shutdown, and releases every owned
dependent afterwards while preserving the primary invocation failure. Shared scoped beans keep their scope.

A dependent instance obtained through the `Instance` of `BeanContainer.createInstance()` or of `CDI.current()` is
released by whoever obtained it, with `Instance.destroy()` (section 11.1.13). The SE container is a lookup of its
own, and destroys the dependent instances obtained through it that are left as it closes: after `Shutdown` and the
`@BeforeDestroyed` of the application context, which section 9.6.2 has observed as the container is about to shut
down, and before the application context is destroyed.

An instance has one owner. `Context.get` for a bean of the container hands out the instance its scope holds and
keeps only the creational context it was given, to release with the scope; `AlterableContext.destroy` destroys the
instance the scope holds, a singleton's included, and the next one is created anew. A context an extension adds
for a scope is the context of that scope, and section 6.2 makes it responsible for destroying the instances it
creates: what it still holds as the container closes is left to it.

A bean reports as its injection points the parameters of its constructor - all but the ones Micronaut generates
for an intercepted bean - its injected fields and initializer parameters, and, for a producer method, the
parameters of the method (section 3.2.2).

Micronaut's `@Order` is taken for a priority where an interceptor declares no `@Priority`: it enables and orders
the interceptor. That is an extension of this implementation, not something either specification defines.

What belongs to CDI Full says so rather than answering: decorators, passivation, and building a bean out of an
annotated type. A subset of the portable extensions of CDI Full is offered beyond Lite, described under
[A portable extension is run over what was compiled](#a-portable-extension-is-run-over-what-was-compiled). The expression language is the one named exception, provided beyond
Lite by the optional `micronaut-cdi-el` module over `micronaut-jakarta-el`: with it on the classpath,
`getELResolver` answers with a resolver in which a name at the base of an expression is the bean of that name —
a name written as a list of identifiers separated by periods included —
and `wrapExpressionFactory` wraps a factory so that what it creates evaluates with the container's beans in
reach, a dependent bean being created once for an evaluation and destroyed as it completes; without it, both
say the module is missing. The manager is implemented because a program
written against the specification reaches for it — the kit's own tests do — not because CDI Full is claimed.

### A producer compiles wherever it is declared

*Section 3.2 / annotated discovery.* Under Lite's annotated discovery a class with no bean defining
annotation is not a bean, and a producer it declares is inert. Here the producer is compiled regardless,
because bean-archive membership is a per-deployment question a global compilation cannot answer — the SE
bootstrap's {@code addBeanClasses} makes exactly such a class a bean by fiat. A producer in a class no
deployment ever admits is the difference visible to code that counts beans.

### Two findings of a reading of ArC's test suite, reported as the class compiles

Both are reported by the compiler rather than at runtime, so neither has a test that runs. A disposer method is
bound to a producer by the rules of typesafe resolution (section 3.4.3), and the qualifiers are compared here for
equality and one occurrence at a time: a producer and a disposer qualified with the same repeatable qualifier
written twice do not match, and every such disposer matches every such producer, so the class is refused with
both "more than one disposer" and "no producer". And an interceptor binding declared on a class reaches the
producer methods of that class, so what a producer of an intercepted class produces is proxied as though it were
intercepted — which a primitive cannot be, and the class is refused.

### An asynchronous event is notified on Micronaut's executor of blocking work

*CDI 4.1 section 2.8.5.* The specification leaves the executor of `fireAsync` without `NotificationOptions` to the
container. Here it is Micronaut's executor of blocking work (`TaskExecutors.BLOCKING`), read as the first event
is fired asynchronously, since an observer may block as any other code does; a context that has no such executor
notifies on the common pool. An executor the firing names is used as it is.

### The dependent instances created for one use go with it, the last created first

*CDI 4.1 sections 2.8.4, 5.1.1 and 10.4.* A dependent instance an `Instance` creates belongs to the bean the
lookup was injected into and goes with it; one created for a notification of an observer method - the dependent
observer bean, a dependent parameter - goes as the notification completes; one created through a
`CreationalContext` goes as it is released; one a synthetic bean's creation function looks up goes with the bean
it created, and one its disposal function looks up as the disposal returns. Micronaut owns them, through the
resolver of the bean and through dependency groups (`OwnedDependents`), and destroys each set in the reverse of the
order it was created in, every one of them attempted where one fails. As the context stops Micronaut opens a
new group for a listener of its shutdown event alone; what a lookup that runs later creates - from a
`@PreDestroy`, or on the thread of an executor - is held by the container and destroyed the same way.

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
`DeploymentException` the test asserts — deployment here *is* compilation. What the container validates as it
starts is reported the same way, from the cause chain of the failure to start; the adapter has no validation of
its own. An SE test the kit runs without Arquillian bootstraps over the class path its own `Deployment` declares,
as it would in the JVM the kit launches for it. An archive carrying a build
compatible extension is likewise compiled per deployment, with that archive's extensions alone.

Two SE bootstrap tests are left out by name, each resting on what belongs to CDI Full and is refused rather
than pretended here: `BootstrapSEContainerTest`'s `testAddDecorator` (decorators), and
`TrimmedBeanArchiveSETest`, whose portable extension would now run but whose archive is a trimmed bean archive
(section 24.4.3) with a producer that takes an `InterceptionFactory`, both of CDI Full. The four that hand the bootstrap a portable extension -
`testAddExtensionAsExtensionInstance`, `testAddExtensionAsClass`, `CustomClassLoaderSETest` and
`CustomRequestContextSETest` - run, on the subset of portable extensions offered beyond Lite. The kit's
deployments share one class path here, so the extensions its SE archives declare as service providers are
listed in one service file of the module, and a bootstrap admits the ones whose class is in its deployment.

The Jakarta Interceptors kit runs here against this container. Micronaut Jakarta Interceptors runs the same kit
in its own build against a stand-in for CDI of four classes (its `cdi-tck-support`), which repeats this module's
mappers of the dependent, request and application scopes and its reading of producers: that build cannot use
this module's processor, which depends on it, so the two are kept in step by hand.

The `tckSuite` task runs the suite of `tck-suite.xml`: the kit's CDI Lite `tests/**` packages — the SE bootstrap
and the CDI 4.1 invokers included — together with the Jakarta Interceptors kit (`interceptors/tests/**`). The
kit has 816 test methods outside `tests/full`, and 811 of them run, all passing, as part of `check`. The five
that do not are the two SE tests above and three methods the kit itself tags `cdi-full` inside Lite packages,
which the exclusion of that group drops, all three about passivation:
`event.implicit.ImplicitEventTest#testImplicitEventIsPassivationCapable`,
`lookup.clientProxy.ClientProxyTest#testSimpleBeanClientProxyIsSerializable` and
`lookup.dynamic.builtin.BuiltinInstanceTest#testInstanceIsPassivationCapable`. A handful of ported assertions and
`ScenarioSweepTckTest` — which reads every scenario bean through one container at once — remain as local
regression tests beside the kit's own.

Beyond Lite, the suite runs a few of the classes the kit marks as CDI Full, in a `beyond-lite` block of their
own: 22 tests, all passing, which makes 833 in all. Each asserts something this implementation answers although
the kit files it under Full — the bean manager's comparison and hash code of qualifiers
(`QualifierEquivalenceTest`), an injectable reference that is unsatisfied or ambiguous
(`UnsatisfiedInjectableReferenceTest`, `AmbiguousInjectableReferenceTest`), interceptors bound with
`@Interceptors` (`MethodLevelInterceptorTest`, `InterceptorBindingsWithAtInterceptorsTest`,
`InterceptorOrderTest`), the definition error of injecting the metadata of a decorator — `Decorator<X>` or
the `@Decorated` `Bean<X>` — into a bean that is not one (the four tests of
`implementation/builtin/metadata/broken/injection`), and the expression language of `micronaut-cdi-el`: names
resolved to beans (`full.lookup.el.ResolutionByNameTest`), a wrapped factory of someone else's
(`WrapExpressionFactoryTest`), and the dependent beans of an evaluation
(`full.context.dependent.DependentContextTest`). A method expression invokes a bean's method through the
executable metadata compiled for it rather than reflectively, so where `micronaut-cdi-el` is on the classpath an
application is compiled against, the processor compiles the public methods of each bean class that has a name
as executable methods; a bean without a name, and an application without the module, gets none of it. Their scenario packages are compiled by name (`beyondLiteScenarios` in
`cdi-tck/build.gradle`), and the `cdi-full` group stays excluded from every other block. CDI Full as a whole is
still not claimed.

The `tckSignatureTest` task checks the API dependencies resolved with `micronaut-cdi-reflection` against the
official CDI 4.1.0 `sigtest-jdk17` baseline, using Jakarta SigTest 2.2 and its Java 17 platform API view.
It checks `jakarta.decorator`, `jakarta.enterprise` (including subpackages), and `jakarta.interceptor`.
The EL API is supplied only to resolve the deprecated BeanManager signatures. The task is part of `check`;
its report is `cdi-tck/build/reports/tckSignatureTest/signatures.txt`. Passing this API check alone does not
establish TCK conformance; the runtime and language-model tests are separate requirements.

The kit has a second part, `jakarta.enterprise:cdi-tck-lang-model`: 985 `assert` statements, and 186 calls of
its own assertion helpers, about the language model of
section 12 — the `ClassInfo`, `MethodInfo`, `Type` and `AnnotationInfo` a build compatible extension reads a
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
in null-marked code, since the kit asserts on the source alone. Seventeen of the kit's eighteen sections pass on
Micronaut Core 5.3, `AnnotatedTypes` among them: Core reports the annotations written on each array dimension, and
the annotations of a type variable's use rather than of its declaration, in Java, Kotlin and Groovy alike.
`RepeatableAnnotations` remains pending for a repetition written beside a hand-written container, which Core
folds into one container (see [Open points in Micronaut Core](#open-points-in-micronaut-core)).
Because the model is built on the AST alone, `test-suite-kotlin`
runs a build compatible extension against a Kotlin class as KSP compiles it, and `test-suite-groovy` against a
Groovy class.

## Open points in Micronaut Core

What this module works around in Micronaut Core, or accepts from it, as of Micronaut Core 5.3.
The provider hook, fresh registrations, dependency groups, destruction ownership and type-annotation fixes
are merged upstream; the remaining differences below still require adaptation or are accepted limitations.
Micronaut Core 5.3 also hands a resolution customizer the context it serves (`BeanResolutionCustomizer.forContext`),
so `CdiResolutionCustomizer` reads the type closures the processor recorded from that context rather than being
handed them as the first lifecycle event's registry is created: a parameterized lookup made before then is matched
by the rules of section 2.4.2.1 as well, and a builder that builds two contexts serves each with a customizer of its
own rather than the first one alone.

- **Annotations as the source wrote them are derived, not recorded.** A repeatable annotation is folded into its
  container, and what Micronaut's mappers add is not told apart from what the source wrote. The language model
  unfolds a container of one repetition and filters Micronaut's own annotations
  (`LanguageModelAnnotationFilter`); a repetition written beside a hand-written container is reported as one
  container of all of them, which is where the `RepeatableAnnotations` section stops and runs as a skipped test.
  A remapped annotation's original name is not recovered either.
- **`MethodElement.getReceiverType()` is empty unless the source wrote the receiver.** Its documentation says an
  instance method has one derived from the declaring type; the Java implementation answers only a written `this`
  parameter, and the Kotlin and Groovy ones never do. `ElementMethodInfo.receiverType()` supplies the declaring
  type itself.
- **A field's annotation metadata does not include its declaring class's, a method's does.** The priority that
  selects an alternative producer field is therefore read from the declaring class explicitly
  (`ProducerVisitor.selectIfAlternative`).
- **What a Kotlin compilation cannot answer.** KSP has no package element, so the annotations of a package read
  as none; a Java record seen from KSP is not known to be one; thrown types come only from `@Throws`. The
  language model answers less in a Kotlin compilation in those places. KSP also answers the members of an
  annotation interface as its properties rather than its methods, which is where the processor and the language
  model read them from there (`ElementMembers.annotationMembersOf`).
- **`AnnotationElement` cannot list the members of an annotation interface.** There is no query of Micronaut's AST
  that answers the members of an annotation interface under every compiler: javac and the Groovy compiler answer
  them as methods, KSP as properties and no methods. The members are enumerated one way or the other in one place
  (`ElementMembers.annotationMembersOf`), which the processor's non-binding members and the language model's
  annotation members and methods read; a member query on `AnnotationElement` would remove it.
- **Micronaut's annotation qualifiers compare the qualifiers a bean was compiled with.** Since Micronaut Core 5.3
  `Qualifiers.byAnnotation(Annotation)` and `byAnnotation(AnnotationMetadata, AnnotationValue)` select by the
  annotation alone; the qualifiers read off an injection point (`byAnnotation(AnnotationMetadata, Class)` and its
  `String` form) still fall back to the simple name of the candidate's class, the convention by which `@V8 Engine`
  selects a `V8Engine`, which a qualifier of the specification does not follow. Either compares a candidate by the
  qualifiers its metadata records, while the qualifiers of a bean of the specification are also the ones a portable
  extension added as the container started, and `@Default` where it declares none. An injection point of a bean
  of the specification, a lookup and the bean manager here select their beans by the container's own record of
  each bean's qualifiers (`CdiBeanContainer.beansOf`) rather than through a Micronaut `Qualifier`.
- **Micronaut's `@Any` on a bean satisfies every lookup.** A bean carrying `io.micronaut.context.annotation.Any`
  is selected by any qualifier; the specification's `@Any` on a bean is inert, so the processor reads it into a
  marker of its own (`CdiAny`) rather than into Micronaut's.
- **A member an extension marks `@Nonbinding` is the processor's to record.** Since Micronaut Core 5.3 Micronaut
  lists every `@NonBinding` member of an annotation under `$nonBinding` on each occurrence, written or not, and on
  its qualifier stereotype, and guards the reading of the members' own annotations against two annotation interfaces
  whose members are annotated with each other (`MutuallyAnnotatedQualifierMembersTest` holds the shape). What it
  cannot see is a member a build compatible extension marked during discovery, which the source does not, and a
  qualifier an extension composed for a synthetic component, which is no occurrence it reads: the processor lists
  those (`NonbindingMembersRemapper`), and the comparison leaves out a member either side lists.
- **`AnnotationValue.matches` is not a full structural comparison.** It fills in the defaults of both sides, but
  then compares what it holds with `equals`: an enum constant a program wrote is not the name compiled metadata
  holds, a class is not its `AnnotationClassValue`, and a nested annotation value passes over a member the other
  one does not have; nor does it leave out the members either side lists under `$nonBinding`. The container
  compares qualifiers and interceptor bindings through the Jakarta Interceptors implementation's
  `BindingValues.matches` instead (`CdiQualifier.matches`), the comparison its chains are built from.
- **Micronaut's exact type arguments are in its reflection module.** Since Micronaut Core 5.3
  `ReflectionArguments.ofExact`, `toExactType` and `typeClosureOf` keep a wildcard a wildcard and a type variable
  a variable, as the rules of section 5.2.4 need, and answer the parameterized type closure of a class; the
  reflection module of this one reads the type closure of a class, and the closure the event type of a class is
  resolved through, with them (`ReflectiveCdi.typeClosureOf`, `eventTypeOf`), adding only what an array
  implements, which `typeClosureOf` does not list. They are in `micronaut-reflection`, which reads classes back,
  and the runtime of this module does not: a `java.lang.reflect.Type` a program hands in is still read into an
  `Argument`, and one the specification's API reports made from one, in `io.micronaut.cdi.internal.type`, by the
  accessors of the type alone. The reflection module reads a type it is handed with `ofExact` itself, the ones the
  container rendered there as well: since Micronaut Core 5.3 `ofExact` reads a variable with no annotated bounds,
  as a variable the container renders is, through its bounds. A variable `toExactType` renders has
  no declaration and is equal to another so rendered, not to the variable the class declares, so the reflection
  module tells the variables of an event's class apart by their names.
- **A lookup that stands on its own cannot carry the injection point it stands for.** Since Micronaut Core 5.3 a
  definition chosen within a creation is resolved within it (`RuntimeBeanDefinition.LookupContext.getBeanRegistration`
  and `BeanResolutionContext.getBeanRegistration`): the lookup a synthetic bean's creation function is handed
  resolves the injection point metadata so (`OwnedDependents.registrationAtCurrentPoint`), and the `InjectionPoint`
  bean finds the point the dependent synthetic bean is created for on the path of the resolution, as it does for a
  bean injected there. What is left is `Instance.get()` and its handles: they create the dependent bean they
  selected by its definition (`getBeanRegistration(definition, argument)`) in a resolution of their own, since the
  lookup is not made within the creation of a bean and `BeanContext` has no resolution context to resolve within,
  while section 2.5.2.5 gives the bean the injection point the `Instance` was injected into, or the lookup itself.
  That injection point is left on a thread local around the creation (`CurrentInjectionPoint`), where the
  `InjectionPoint` bean reads it when the path has nothing to say; a definition-keyed lookup that took the
  injection point to report, or an attribute the new resolution starts with, would let it travel with the
  resolution instead.
- **The attributes of a visitor context are kept differently by each compiler.** The processor keeps what one
  visitor decides for another, or for a later round, on the attributes of the compilation's `VisitorContext`
  (`CompilationState`) rather than in static fields. Under javac they belong to one
  processor and last for its compilation: the visitors share them, but annotation metadata built for the bean
  definition processor is remapped with that processor's attributes, so there a remapper does not see the
  extensions' discovery phase — what the phase registered is also put on the annotation types themselves, which
  that metadata reads. Under KSP they belong to the processor and last across its rounds. The Groovy compiler keeps
  them in one static map for every compilation in the process (`GroovyVisitorContext`), so under Groovy two
  compilations running at once in one process share them as they would a static field; the visitor of the
  compilation is kept there. Since Micronaut Core 5.3 (`TypeElementVisitor.finishRound` and `VisitorContext.registerBean`)
  the phases of the build compatible extensions run as a round finishes and their beans are registered without a
  source, so the visitor and the records of the components no longer wait on the attributes for a generated marker
  or factory to come past, and neither does the type index, registered the same way; what is left there is the
  visitor the compilation started, read by the registration visitor.
- **The parameters Micronaut generates for the constructor of an intercepted subclass are named only in private
  constants.** The subclass Micronaut generates for an intercepted bean takes the resolution context, the bean
  context, the qualifier, the interceptors and the interceptor registry after the author's parameters, under names
  that are private constants of `AopProxyWriter`, and how many there are is passed to generated code alone. A
  definition of such a subclass is an `AdvisedBeanType`, so the container leaves those names out of the
  constructor of that definition alone (`CdiResolution.isGeneratedConstructorParameter`, `isProxyDefinition`) when
  it describes or validates the injection points; a parameter of any other constructor or method so named is the
  author's. A public list of the names, or the intercepted constructor's own arguments on the definition, would
  remove the copy.
- **A mark on an inherited member is a mark for every class that inherits it.** Micronaut builds the annotation
  metadata of a field and of a parameter once, by the class declaring the member
  (`AbstractElementAnnotationMetadataFactory`), so an annotation a visitor adds while one subclass is visited is
  read through every other. The processor therefore marks the points a class declares alone
  (`ContainerInjectionVisitor`), and the points a bean of the specification inherits from a class that is not one
  keep Micronaut's rules; metadata of a member kept by the class it is read through would let the container
  resolve them as well.
- **A null dependent cannot be destroyed.** `DefaultBeanContext.triggerPreDestroyListeners` hands every
  `BeanPreDestroyEventListener` the bean being destroyed and requires each to return a bean, so the registration of a
  dependent a producer satisfied with null fails as it is destroyed with its owner, and the listener the container
  disposes of a product with (`DisposerInvoker`) cannot answer it. A primitive parameter of an observer or a disposer
  is therefore resolved outside the dependency group of the invocation; skipping the listeners for a null bean
  would remove that.
- **Micronaut chooses one bean among candidates in a private method.** `DefaultBeanContext.lastChanceResolve` -
  the primary ones, `BeanResolutionCustomizer.resolveNonUniqueBean`, `@Secondary`, the order, `@DefaultImplementation`
  and the exact type - is reached only through a lookup by a type and a qualifier, which selects the candidates
  itself; the bean manager's `resolve` is handed a set of beans and no type. The container copies the precedence
  into one method (`CdiResolution.amongMicronautsOwn`), which a public selection over a given collection of
  definitions would replace; it asks the customizer the context was configured with, since Micronaut does not
  publish the one it serves the context with.
- **The context is injected under types no bean definition stands for.** `DefaultBeanContext` hands itself out as
  `BeanContext`, `WatchableBeanContext`, `ApplicationContext`, `BeanLocator`, `BeanDefinitionRegistry`,
  `ExecutionHandleLocator`, `AnnotationMetadataResolver`, `PropertyResolver`, `ValueResolver` and
  `PropertyPlaceholderResolver`, listed in its private `thisInterfaces`, makes a `BeanDependencyResolver` and an
  `InterceptionTarget` for the bean being resolved, and resolves `io.micronaut.inject.InjectionPoint` and a
  `Qualifier` from the resolution under way; none of them has a definition the container could see. A marked point
  no bean of the specification answers is left to Micronaut as it is injected, which resolves these as well, so the
  copy of the list the processor keeps (`ContainerInjectionVisitor.CONTEXT_TYPES`) is not what injects them: it
  keeps them out of the deployment validation, which asks Micronaut for a definition of what such a point resolves
  to, and keeps a bean of the specification that implements one of them from taking the context's place. A public
  list, or a definition for each, would remove the copy.
- **The static state that remains is the specification's.** `CDI.current()` is a static entry point with nothing
  to hand it, and resolves to the container started last (`CdiRunning`), which the container itself never asks;
  `AnnotationBuilder.of` reaches the `BuildServices` through the specification's static resolver, set as each
  compilation starts; the request context and `RequestContextController` follow the specification's per-thread
  model with thread locals of each container's own. Two seams are left for the harnesses that run the
  specification's test kit in one process: the extensions of one compiled deployment
  (`BuildCompatibleExtensionVisitor.overrideExtensions`), since a compilation is handed nothing but options, and
  the classpath a test's SE container sees (`MicronautSeContainerInitializer`), since the kit builds the container
  itself through `SeContainerInitializer.newInstance()`.

## What other implementations' tests found

The tests of other implementations are read as a catalogue of cases rather than run here: Weld, the reference
implementation, holds its reading of the type rules of section 5.2 to a table of type pairs — raw types,
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
