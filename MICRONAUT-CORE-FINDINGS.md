# Micronaut Core findings from building CDI Lite

What implementing the CDI Lite TCK on top of Micronaut surfaced about Micronaut itself: bugs found, fixes made in
the local checkout (`../micronaut-core`, published to mavenLocal), and API gaps worth taking upstream. Each entry
names where the behaviour lives and what this project does about it meanwhile.

## Bugs

### 1. A `@Primary` bean with another qualifier NPEs when resolved by its produced bean — FIXED upstream (#12945)
`BeanDefinitionWriter.getQualifier` maps `@Primary` to a literal null qualifier; composed with another qualifier
that null lands in the `Qualifiers.byQualifiers(...)` array and `CompositeQualifier.filterQualified` dereferences
it. Fixed in the local checkout by leaving `@Primary` out of composite qualifiers
(`core-processor/.../writer/BeanDefinitionWriter.java`); regression spec added at
`inject-java/src/test/groovy/io/micronaut/inject/cdiscenarios/PrimaryQualifiedFactorySpec.groovy`.

### 2. `AbstractConcurrentCustomScope.remove(BeanIdentifier)` closes the bean but never removes it — FIXED upstream (#12947)
`inject/.../context/scope/AbstractConcurrentCustomScope.java`: `remove` does `scopeMap.get(identifier)` +
`close()`, so the destroyed instance stays in the map and keeps being served — a client proxy resolves the
*destroyed* instance forever after. Found via the TCK's `AlterableContextTest`. Fixed locally with
`scopeMap.remove(identifier)`; this project also removes-then-closes in its own scopes
(`cdi/.../context/ApplicationScope.java`, `RequestScope.java`) so it works against the published snapshot too.

### 2a. Interceptor lifecycle, two fixes made for this project — FIXED upstream (#12920, #12922)
`@PreDestroy` methods were not invoked on a bean carrying `PRE_DESTROY` advice (#12920), and an interceptor
instance was not the same across a bean's construction, method interception and destruction (#12922). Both
matter to Jakarta Interceptors' lifecycle contract (one interceptor instance per intercepted instance, whose
`@PreDestroy` sees what its `@PostConstruct` acquired), which the CDI TCK's interceptor kit asserts.

### 3. `Qualifiers.byAnnotation(Annotation)` compares by type only, ignoring members — FIXED upstream
A bean qualified `@Chunky(true)` matched a lookup for `@Chunky(false)`. Merged into 5.2.x as
"Compare the members of a qualifier built from an annotation instance" (#12928). This project routes every
lookup through `CdiQualifiers`, which builds the qualifier from the annotation's values, so it was unaffected
either way.

### 4. `@Bean(typed = double.class)` — primitives rejected in exposed types — FIXED upstream
Merged into 5.2.x as "Allow a primitive type to be named among a bean's exposed types" (#12927). Also merged
alongside: "Fix an interceptor not matching a bean that leaves a binding member at its default" (#12925) —
binding members are now compared with `AnnotationValue.matches`, so a declared default and an omitted member
read as the same binding.

## API gaps and inconsistencies worth an upstream conversation

### 5. A field's `AnnotationMetadata` does not include its declaring class's annotations; a method's does
`FieldElement.getAnnotationMetadata()` sees only what the field declares, while producer-style resolution often
needs the class context (`@Priority` on the class selecting an `@Alternative` producer field). This project works
around it by consulting the declaring `ClassElement` explicitly (`ProducerVisitor.selectIfAlternative`). Either
behaviour is defensible, but methods and fields disagreeing is a trap.

### 6. `jakarta.annotation.Priority` is remapped to `@Order` with the value as-is — and the original is dropped
Two consequences: (a) code looking for `@Priority` in metadata finds nothing after mapping, so compile-time reads
must check both forms (`cdi-processor/.../Cdi.priorityOf`); (b) the sign convention inverts the meaning — CDI
priority prefers the highest value, Micronaut order the lowest — so a CDI-selected alternative must write
`@Order(-priority)` *over* the mapped value. A definition-level `getPriority()` (or documented mapping) would
remove the trap.

### 7. NOT REPRODUCED — `getAnnotationNamesByStereotype` names the immediate carrier, not the declared annotation
A javac probe on `Class → @S → @RequestScoped → @NormalScope` (in-source and precompiled `@S`, `@Inherited`
too) answers `byStereotype(NormalScope) = [RequestScoped]` — the immediate carrier, as
`MutableAnnotationMetadata` records `CollectionUtils.last(parentAnnotations)`. Only a *repeatable* stereotype
records the whole chain. `CdiScopeVisitor.resolveToScope` works either way, so nothing here was at risk; the
original text below is kept for the record.

#### 7 (original). `getAnnotationNamesByStereotype` returns the *declared* annotation for transitive chains
For `Class → @SomeStereotype → @RequestScoped → @NormalScope`, asking for annotations carrying `NormalScope`
returns `SomeStereotype`, not `RequestScoped` (`DefaultAnnotationMetadata.java:1133`, the
`annotationsByStereotype` index). Correct for "what did the author write", but callers expecting the stereotype's
*subject* must resolve through the annotation's own metadata (`CdiScopeVisitor.resolveToScope`). Worth
documenting; a `getAnnotationCarryingStereotype`-style API would express both needs.

### 8. `java.lang.annotation.*` meta-annotations are stripped from metadata — `@Inherited` is unqueryable
`hasDeclaredAnnotation(Inherited.class)` is always false. CDI's §2.3.1 inheritance rules turn on `@Inherited`, so
this project reads it off the native `javax.lang.model` element (`CdiScopeVisitor.inheritedOnTheSourceElement`,
unwrapping `ClassElement.getNativeType()` reflectively). An `ElementMetadata.isInherited()`-style accessor on
`ClassElement` would avoid the native unwrap.

### 9. No unfiltered, predicate-aligned view of the compiled definitions
CDI's `getBeans` must return *unresolved* candidates — a selected alternative and the bean it outranks together —
while every Micronaut lookup resolves as it goes (replacement filtering, primary narrowing).
`getBeanDefinitionReferences()` works as the raw view, but an environment that narrows the context via
`ApplicationContextBuilder.beansPredicate(...)` cannot read that predicate back from the context, so this project
carries the same predicate twice (`DeploymentBeanFilter`). Exposing the configured predicate on
`BeanContext`/`ApplicationContextConfiguration` would remove the duplication.

### 10. RETRACTED — `getBeanRegistration(BeanDefinition)` has existed since 3.5.0
Claimed that creating an instance *of a specific definition* with dependents tracked (CDI's `Bean.create` +
`CreationalContext.release`) needed a new overload, and pinned the definition with a custom `Qualifier`
filtering candidates by identity. `BeanDefinitionRegistry.getBeanRegistration(BeanDefinition<T>)` is marked
`@since 3.5.0` and does exactly this — `DefaultBeanContext` implements it as
`resolveBeanRegistration(null, beanDefinition)`, bypassing candidate reduction entirely, which is what the
hand-rolled qualifier was emulating. The finding was never checked against the API surface.

Three copies of that workaround (`CdiBean.thisDefinitionOnly`, `DisposerInvoker.registrationOf`,
`CdiObserverMethod.registrationOfDeclaring`) are gone in favour of the overload.

### 11. `@InjectScope` on a constructor/factory parameter never destroys — two compounding bugs — FIXED upstream (#12934)
(a) `BeanDefinitionWriter.hasInjectScope()` passes the constructor/factory `MethodElement` into the
`AnnotationMetadata` overload, checking the *method's own* annotations instead of its parameters — so
`destroyInjectScopedBeans()` is never emitted for constructor-parameter `@InjectScope` (field and
`@PostConstruct`-method paths do iterate parameters). (b) `DefaultBeanContext.findCustomScope` returns `null`
immediately for `@Prototype` definitions, so the injection-point-declared scope check further down never runs —
`@InjectScope` works for *unscoped* beans but silently does nothing for prototype ones (`@Singleton` correctly
outranks it per the annotation's javadoc; prototype should behave like unscoped). Found because CDI's
`@TransientReference` maps to `@InjectScope` and `@Dependent` maps to `@Prototype`: the TCK's
`DependentTransientReferenceDestroyedTest` never saw the destruction. Both fixed in the local checkout
(`core-processor/.../writer/BeanDefinitionWriter.java`, `inject/.../DefaultBeanContext.java` —
`findInjectionPointDeclaredScope` extracted and reached from the prototype branches).

### 12. `BeanResolutionCustomizer` has no say when an instantiation returns null — RESOLVED, no core change needed
A CDI producer may legitimately return null, and `DefaultBeanContext.resolveByBeanFactory` throws "returned
null" before `resolveNullBean` is consulted — unless the definition carries the `Nullable` stereotype, which
lets the null through to `resolveNullBeanRegistration` and the customizer's `resolveNullBean` (#12678). That
condition is the extension point: `ProducerVisitor` annotates every producer with the stereotype at compile
time (section 3.2.2 says a producer may return null), so no runtime hook is needed. A `shouldAllowNullBean`
customizer hook was tried locally and upstreamed as #12936, then withdrawn as redundant; the local patch is
removed. The proxy-target half of the story is separate and still needed: #12933 (finding #17).

### 13. The CDI hooks of #12678 work — notes from wiring them
`beanResolutionCustomizer` carried this project through generic-variable resolution (isCandidateBean), array
bean types (shouldResolveArrayAsBean), primitive boxing (resolveBeanLookupArgument), null producers
(resolveNullBean), and CDI's proxy-based circular-dependency breaking (shouldInitializeBean=false for client
proxies, shouldPreserveLazyProxyTargetResolutionPath=false). One trap: `BeanRegistration.close()` is a no-op
unless the definition is disposable or dependents exist — destruction that must fire
`BeanPreDestroyEventListener` (this project's disposer invocation) has to go through
`beanContext.destroyBean(registration)` instead.

### 13a. The `BeanRegistration.close()` no-op trap — FIXED upstream (#12943)
`BeanRegistration.of(context, ...)` returned a plain registration — whose `close()` is a no-op — unless the
definition had dispose logic, dependents, or was a `LifeCycle`; destruction listeners were silently skipped
for everything else. The local tree now always returns the disposing registration (closing destroys through
the context), matching the open upstream PR #12938. A custom scope's `destroyScope` closing its `CreatedBean`s
now reaches `BeanPreDestroyEventListener`s — which is what lets a request-scoped synthetic bean's disposal
function run when the request ends.

### 13b. Closing a registration twice destroyed the bean twice — FIXED upstream (#12956)
A consequence of 13a: once `BeanRegistration.of(context, ...)` always returned a `BeanDisposingRegistration`,
its `close()` ran `beanContext.destroyBean(this)` every time it was called, so a second `close()` ran
`@PreDestroy` a second time. CDI reaches this easily — a lookup destroys the dependent instances it created
when it is closed, and an `Instance.Handle` may already have destroyed one of them. `BeanDisposingRegistration`
now guards with an `AtomicBoolean`, so the first close destroys and later ones are no-ops.

This project keeps its own bookkeeping (removing a registration from the lookup's list before closing it, and
the handle's `destroyed` flag) because the non-registration path still goes through `destroyBean`, but
correctness no longer depends on that bookkeeping being perfect.

### 13c. `ExecutableMethod` exposes no modifiers — not worth asking core for
`ExecutableMethod` declares only `isAbstract()`/`isSuspend()`; asking whether a compiled method is public means
`getTargetMethod()`, which `AbstractExecutable` resolves through `ReflectionUtils.getRequiredMethod` +
`setAccessible(true)` (and `AbstractExecutableMethodsDefinition` logs it as "Reflectively accessing method").
So a compile-time-known fact cost a reflective lookup that throws `NoSuchMethodError` where the method is not
registered for reflection.

Not raised upstream: the answer is known while the disposer is compiled, so `@CdiDisposer` simply records it
(`publicMethod`). No core change, no metadata beyond one boolean on an annotation this project already writes.

### 14. `ThreadLocal` custom scope destroys beans only with `lifecycle = true` (not a bug — a doc trap)
`@ThreadLocal` beans are not destroyed on scope end unless `lifecycle = true` is set; the flag is easy to miss
and initially read as a dependent-destruction bug during this project (it was not — behaviour is by design and
documented on the annotation).

### 15. A static method meta-annotated `@Executable` got no `ExecutableMethod` — FIXED upstream (#12957)
Stated too broadly at first: a `static` method annotated **directly** with `@Executable` already worked —
`DeclaredBeanElementCreator` had a carve-out for it and `DispatchWriter` emitted `invokestatic`. The real gap
was narrower: the gate asked `hasDeclaredAnnotation(Executable.class)`, which is false for an annotation that
is itself meta-annotated `@Executable` — so essentially every executable annotation in the ecosystem
(`@Get`, `@Scheduled`, and this project's `@CdiObserver`) was silently dropped on statics. The gate now asks
for the `@Executable` **stereotype** on the method's own metadata, with adapter advice (`@EventListener`)
still excluded because it adapts an instance method and cannot apply to a static.

Adopted here: static observers are dispatched through their generated `ExecutableMethod` like any other, so
the class-level `@CdiStaticObservers` index, its signature encoding, `StaticObserverMethod`, and the
`@ReflectiveAccess` marking of static observers are all gone — roughly 200 lines of reflective dispatch, and
one fewer reason for this container to reflect at runtime. Observers needed the fix because `@CdiObserver`
carries `@Executable` as a meta-annotation.

Static **disposers** were a separate matter, and the fault was entirely ours: `ProducerVisitor` writes a
*direct* `@Executable` on a disposer, which already worked on statics before #12957. The reflective
`invokeStatic` in `DisposerInvoker` rested on the same over-broad belief and never needed to exist. It is
gone too, and `StaticDisposerTest` now covers the case — there is no static disposer anywhere in the TCK, so
that path had no test at all before.

### 16. Inherited executable methods keep the declaring class's unresolved type variables
An executable method declared on a generic superclass and inherited into a concrete subclass keeps the
superclass's `Argument`s: the type variables are erased to their bounds rather than substituted with the
subclass's actual type arguments (e.g. `AbstractObserver<T>.observe(T)` inherited by `FooObserver extends
AbstractObserver<Foo<String>>` reports `Object`, not `Foo<String>`). Visiting the abstract class can even
record erased metadata onto the shared method element, clobbering the subclass's view. This project rebuilds
the observed type reflectively (`getGenericParameterTypes()` + a substitution map walked from the concrete
class) — core substituting variables when copying inherited executable methods would remove that need.

### 17. `getProxyTargetBean` skips `resolveNullBeanRegistration` — FIXED upstream (#12951)
The lazy proxy target path (`DefaultBeanContext.getProxyTargetBean`) returns `registration.bean` directly, so a
factory/producer that legitimately returned null (allowed via `shouldAllowNullBean`) hands the proxy a null
target and the intercepted call throws NPE — while the ordinary lookup path routes the null through
`resolveNullBeanRegistration` where a customizer can substitute or throw (CDI throws
`IllegalProductException` for a non-dependent producer). Patched locally: both overloads now consult
`resolveNullBeanRegistration` when the resolved bean is null.

### 18. `@InjectScope` destruction misses pre-destroy listeners; the scope instance was JVM-global — FIXED upstream (#12934)
`DefaultCustomScopeRegistry.InjectScopeImpl.stop()` called `CreatedBean.close()`, which is a no-op for a
registration whose definition has no pre-destroy of its own (`BeanRegistration.of` only returns a disposing
registration for `DisposableBeanDefinition`/`LifeCycle`/dependents) — so `BeanPreDestroyEventListener`s (CDI's
disposer methods) never ran for `@InjectScope`-destroyed beans. Also `INJECT_SCOPE` was a `static final`
singleton with a mutable `currentCreatedBeans` list, shared by every `ApplicationContext` in the JVM. Patched
locally: one `InjectScopeImpl` per registry, and `stop()` destroys through the `BeanContext` so listeners run.

### 19. `@ClassImport` is never processed as a top-level trigger — FIXED upstream (#12952)
`BeanDefinitionInjectProcessor` filters the round's annotations with
`lookupOrBuildForType(ann).hasStereotype(ANNOTATION_STEREOTYPES)`; `ClassImport` is listed in that array, but an
annotation type never carries itself as a stereotype, so a class annotated only with `@ClassImport` (plus, say,
`@Generated`) is silently skipped and no imports happen. Additionally `ModelUtils.resolveTypeElements` drops any
element annotated `@Generated` — reasonable for Micronaut's own outputs, but it means a *generated* importer
class must not carry `@Generated`. Patched locally: the filter admits `ClassImport` by name. Found because this
project generates a `@ClassImport` source to turn extension-scanned classes (CDI `ScannedClasses.add`) into
beans.

### 20. No way to observe the resolution path from outside a resolution — RESOLVED upstream (#12937)
Nothing tells an integration *why* the bean it is constructing at runtime is being constructed — CDI 2.10.5
requires a synthetic (runtime-registered) dependent bean's creation function to see the `InjectionPoint` it is
being created for, but a `RuntimeBeanDefinition` supplier receives no `BeanResolutionContext`. Patched locally:
`AbstractBeanResolutionContext` keeps a thread-local deque of the contexts open on the calling thread — pushed
in the base constructor so both `DefaultBeanResolutionContext` and `DefaultBeanContext`'s
`SingletonBeanResolutionContext` register (a copy taken via `copy()` immediately deregisters: it is stored away
for later, not the resolution under way), popped in `close()` — exposed as static
`AbstractBeanResolutionContext.activeContexts()` (most recently opened first). CDI walks the active paths for
the segment whose argument matches the synthetic bean's type. A cleaner upstream shape might be passing the
resolution context (or the current segment) to `RuntimeBeanDefinition` suppliers directly.

### 21. `RuntimeBeanDefinition.Builder.singleton(boolean)` ignores its argument — FIXED upstream (#12946)
`DefaultRuntimeBeanDefinition.Builder.singleton(boolean isSingleton)` sets `this.singleton = true`
unconditionally, so `singleton(false)` still builds a singleton definition. A runtime-registered bean meant to
be prototype/dependent (CDI's synthetic beans default to `@Dependent`) is created once in the singleton scope,
is never tracked as a dependent of whoever asked for it, and never destroyed with it. Patched locally:
`this.singleton = isSingleton`.

### 22. Resolving from a stopped context throws `BeanContextException` rather than `IllegalStateException` — FIXED upstream (#12948)
`DefaultBeanContext.assertContextState` reports resolution against a context that is not running as a
`BeanContextException` extending plain `RuntimeException`. Using an object in the wrong lifecycle state is what
`IllegalStateException` means in the JDK's own vocabulary — and CDI requires exactly that of a contextual
reference used after the container shut down. Patched locally: `assertContextState` throws
`IllegalStateException`.

### 23. RETRACTED — the repeatable qualifier was dropped by this project, not by core
Recorded as core dropping a `@Repeatable` annotation from a method parameter's metadata. It does not: probing
`origin/5.2.x` directly shows a parameter keeps the container exactly as a field does. What dropped it was this
project's own `InjectedParameters.readAsInjectionPoints`, which strips the qualifiers a parameter did not
declare itself and asked the question two incompatible ways — `getAnnotationNamesByStereotype` answers with the
name the author wrote (`Start`), while the metadata declares the container (`Bootable`), so
`hasDeclaredAnnotation("Start")` said no and the removal took the container with it. Fixed by comparing against
the annotations written on the parameter, container members included.

The one real observation underneath it is a trap worth knowing: for a repeatable annotation,
`getAnnotationNamesByStereotype` reports a name that `hasDeclaredAnnotation` then denies, on every element
kind, and `getDeclaredAnnotationNamesByStereotype` reports nothing at all. Code that pairs those queries will
be wrong about repeatable annotations.

### 25. Mutations of an inherited parameter or field are cached per *owning type* — invisible through a subclass
`AbstractElementAnnotationMetadataFactory` keys parameter metadata as `lookupOrBuildForParameter(owningType,
method, parameter)` (`Key3`) and fields as `(owningType, field)`, although the parameter hierarchy is built from
overridden parameters plus the variable and never includes the owner. Probe: a visitor on `Base` that removed
`@Marker` from `Base.inherited(a)` and added `@Added` — a visitor on `Sub`, which inherits the method, still saw
`@Marker` and never saw `@Added`. Both removal and addition are lost across owners. This is the real reason the
project's static `RemovedAnnotations` registry exists (its javadoc blames visitor views; the probe shows the
owner split). Fix: drop `owningType` from the parameter and field keys. Compile-time only; zero runtime cost.

### 26. Repeatable annotations are invisible to the *declared* queries by name — PR #12963
`DefaultAnnotationMetadata.getDeclaredAnnotationNamesByStereotype` filters the stereotype index — which lists
the **member** (`Q`) — against `declaredAnnotations`, which holds the **container** (`Qs`) → always empty.
`hasDeclaredAnnotation(String)` likewise ignores the container while the `Class` overload maps through
`findRepeatableAnnotationContainerInternal`. Probe on `@Q("a") @Q("b")`: `byStereotype=[Q]`,
`declaredByStereotype=[]`, `hasDeclared(Q)=false`, `hasDeclared(Qs)=true`. Fix: accept `s` when
`declaredAnnotations` contains its container. Tiny, zero overhead. This is the trap behind retracted #23.

### 27. `AbstractConcurrentCustomScope` cannot answer "the instance held for this definition"
`CustomScope.findBeanRegistration(BeanDefinition)` has a default returning empty since 3.5; the abstract scope
implements only the `(T bean)` overload, `remove(BeanIdentifier)` is `final` and the identifier core stores
under (`DefaultBeanContext.BeanKey`) is package-private — so an `AlterableContext.destroy(Contextual)` has to
scan every `CreatedBean` and match proxy target *names* (`context/ApplicationScope.java`, `RequestScope.java`,
two ~40-line copies). Fix: implement `findBeanRegistration(BeanDefinition)` in the abstract scope and add a
non-final `remove(BeanDefinition)` that removes under the lock and closes outside it. Additive.

### 28. `AbstractConcurrentCustomScope.getOrCreate` holds the scope-wide write lock around `doCreate`
Every scoped bean's constructor and `@PostConstruct` in the JVM runs mutually exclusively per scope, even where
the scope hands back a per-request map. An application-scoped `@PostConstruct` that waits on another thread
creating another application-scoped bean deadlocks. The class javadoc admits it is for "a small amount of
beans". Fix: per-key creation (`computeIfAbsent`-style) with `doCreate` outside any lock, as an opt-in base or
flag. Medium; existing subclasses unchanged. micronaut-http's `RequestCustomScope` has the same exposure.

### 29. `DefaultCustomScopeRegistry` caches negative lookups forever — FIXED upstream (#12961)
`findScope` is `scopes.computeIfAbsent(name, …findBean…)` and stores `Optional.empty()` permanently;
`registerBeanDefinition` purges the candidate caches but never the scope registry. A `CustomScope` registered
at runtime (extension-declared contexts, `extension/ExtensionContexts.java`) is invisible if any bean of that
scope was resolved first — and such a bean silently becomes dependent. Works today only by eager-bean ordering.
Fix: `CustomScopeRegistry.invalidate()` (default method) called from `registerBeanDefinition` when the
definition's type is a `CustomScope`. Zero cost on resolution.

### 30. `RuntimeBeanDefinition.Builder` has no disposer hook — PR #12964
The builder offers qualifier/replaces/named/scope/singleton/exposedTypes/typeArguments/annotationMetadata;
`DefaultRuntimeBeanDefinition` is not a `DisposableBeanDefinition`. A synthetic bean's disposal function is
therefore run from a JVM-wide `BeanPreDestroyEventListener<Object>` plus an identity map
(`extension/SyntheticDisposerListener.java`, `SynthesisRunner.creatorLookups`). Fix: `Builder.disposer(
BiConsumer<BeanContext, B>)` making the built definition disposable. Additive, zero overhead. (Project side:
the creator lookup's transient registrations should go through `resolutionContext.addDependentBean` — core
already collects dependents into the registration.)

### 31. No API from a `ProxyBeanDefinition` to its target `BeanDefinition`
Only `getTargetDefinitionType()` (a `Class`) and `getTargetType()`; `getProxyTargetBeanDefinition(Argument,
Qualifier)` re-resolves by type. Six sites here match definition class names (`CdiBean.targetDefinition`,
`canonicalDefinitionName`, `CdiBeanContainer`, `CdiInstance.dedupProxies`, both scopes, `RecordedInvoker`);
core itself does the same internally. Fix: `ProxyBeanDefinition.findTargetDefinition(BeanDefinitionRegistry)`
or `BeanDefinitionRegistry.findBeanDefinition(Class<? extends BeanDefinition<?>>)`. Read-only, off the hot path.

### 32. A resolution segment's kind is only knowable through `@Internal` classes
`FieldSegment` implements `InjectionPoint`/`ArgumentInjectionPoint` but not `FieldInjectionPoint`, and its
`getOuterInjectionPoint()` throws `UnsupportedOperationException`, so `CdiInjectionPoint.of` must `instanceof`
`AbstractBeanResolutionContext.FieldSegment`/`ConstructorSegment` (`@Internal`). Fix: implement
`FieldInjectionPoint` (it has name, argument, declaring bean) and return `null` rather than throw. Zero overhead.

### 33. `destroyBean(Object)` for an untracked proxied bean drops its interceptor registrations
After #12922 a proxy's four interception phases share one interceptor instance, destroyed as a dependent of
the target — except through `destroyBean(T)`'s fallback (`DefaultBeanContext.destroyBean(T)`), which builds
`BeanRegistration.of(this, key, definition, bean)` with no dependents, so a `@PreDestroy` on the interceptor
does not fire there. Core's own lifecycle table lists this row as the exception. Fix: when
`bean instanceof Intercepted i` and `i.$interceptorRegistrations()` is non-empty, pass the non-singleton ones
as dependents. Only that fallback path is touched. Relevant to micronaut-jakarta-interceptors, whose weak-map
per-target bookkeeping #12922 otherwise made redundant.

### 34. `MethodArgumentSegment.getOuterInjectionPoint()` throws for a plain `@Inject` method argument
Found while fixing #32: the segment's `outer` is only set when the previous segment happens to be a
`MethodSegment`, and every production caller pushes method arguments through the
`(BeanDefinition, String, Argument, Argument[])` overload with no `MethodInjectionPoint` at hand — so `outer` is
absent and the accessor throws `IllegalStateException("Outer argument inaccessible")`. After #32 a customizer can
tell a field (`instanceof FieldInjectionPoint`) and a constructor argument (`outer instanceof
ConstructorInjectionPoint`) apart, but still cannot call `outer` safely on a method argument. Fix: return `null`
as `FieldSegment` now does, or carry the method injection point on that overload. Zero overhead.

### 35. `SingletonScope.getOrCreate` releases its per-identity lock after a *failed* creation
Observed while implementing #28: `SingletonScope` removes the per-identity lock object in `finally`, so when a
creation throws, two waiters that arrive afterwards can each create under a fresh lock and both succeed — two
instances of a singleton. `AbstractConcurrentCustomScope`'s new per-bean mode deliberately keeps its lock
objects for the scope's life to avoid exactly this. Unverified by a test; recorded for a reproduction.

## Project-side follow-ups the same audit produced (not core's)
- Replace the static cross-visitor registries with `VisitorContext` attributes (`MutableConvertibleValues`,
  one context per compilation) and seed the TCK adapter's extensions through a `JavaParser` subclass rather than
  `BuildCompatibleExtensionVisitor.overrideExtensions`.
- `InjectedParameters.readAsInjectionPoints` rests on a premise core does not have (a parameter's metadata does
  not carry its method's annotations) — the removal loop removes nothing in the ordinary case; delete it.
- `@Executable(processOnStartup = true)` on `CdiObserver` + `ObserverRegistry implements
  ExecutableMethodProcessor<CdiObserver>` replaces the all-definitions walk; `CdiBeanContainer.canonicalBean`
  wants a map; `Class.forName` for annotation types → `AnnotationMetadata.getAnnotationType(name)`.
- Every normal-scoped bean also carries the `CdiApplicationScope` stereotype (`NormalScopeAnnotationMapper`);
  the runtime picks the right scope by registration order alone — needs a regression test.
- Interceptors bridge: after #12922 the weak `TargetKey`/`ReferenceQueue` bookkeeping in
  `InterceptorLifecycleSupport` is redundant for proxied beans (a `@PreDestroy` on the advice does the same);
  the `@Adapter`-generated index beans can be replaced by `getBeanDefinitions(Qualifiers.byStereotype(
  "jakarta.interceptor.Interceptor"))`, which filters references before loading; docs and the TCK exclusion list
  still say private interceptor methods are rejected while the code accepts them.

### 24. No public way to read an annotation instance as an `AnnotationValue`
`Qualifiers.byAnnotation(Annotation)` (#12928) reads the members off a live annotation and stores them the way
compiled metadata does — a class as `AnnotationClassValue`, an enum by name, a nested annotation as an
`AnnotationValue`, `@NonBinding` members left out — in `AnnotationMetadataQualifier.fromAnnotation` /
`resolveAnnotationBindingValues` / `asMemberValue`. All three are private.

CDI hands the container annotation instances constantly — `Instance.select(Annotation...)`,
`Event.select(...)`, `BeanContainer.isMatchingBean(Set<Annotation>)`, the qualifiers an extension puts on a
synthetic bean — so this project re-implemented the same reader (`CdiAnnotations.valueOf`/`storedForm`, and
again in `SynthesisRunner`). The copy had drifted: it did not convert a nested annotation member, so a qualifier
such as `@Located(region = @Region("east"))` selected by its literal was **unsatisfied** — the live `Region`
proxy never equalled the stored `AnnotationValue`. Fixed here (`NestedAnnotationQualifierTest`), by mirroring
core's conversion.

A public `AnnotationValue.of(Annotation)` (or an `AnnotationUtil` equivalent) is zero-overhead compile-time-free
API that core already has the body of; it would remove the duplicates downstream and, more to the point, the
drift between them.

### 16. RETRACTED — inherited executable methods do resolve their type variables
Recorded as an inherited `@Executable` method keeping the declaring class's unresolved variables. On
`origin/5.2.x` it does not: `JavaMethodElement.getDeclaringType()` resolves the declaring superclass through
the owning subclass's type arguments, and every shape probed — deep chains, interface defaults, precompiled
superclasses, two subclasses in one round, AOP proxies, bridge methods, recursive and intersection bounds, and
the Groovy path — reports the substituted type. The visit-order clobbering half is not reproducible either.

What remains true is narrower and is a different gap: a wildcard degrades to its bound
(`Foo<?>` is compiled as `Foo<Object>`, `List<? extends Number>` as `List<Number>`), because
`io.micronaut.core.type.Argument` has no wildcard representation. That is why this project still rebuilds an
observed type reflectively — for wildcards alone, not for inheritance. Fixing it upstream means extending the
runtime `Argument` model, which is a larger, API-breaking change than the processor-side substitution first
assumed.
